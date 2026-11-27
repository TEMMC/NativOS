package com.mt.gnome

import android.app.Activity
import android.app.AlertDialog
import android.widget.ProgressBar
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.tukaani.xz.XZInputStream

/** Linux distribution catalogue. No terminal emulator or interactive PTY is hosted here. */
class DistroCatalogActivity : Activity() {
    private val alpineRoot = "/data/local/mt-os/distros/alpine"
    private val alpineVersion = "3.24.4"
    private val archiveName = "alpine-minirootfs-$alpineVersion-aarch64.tar.gz"
    private val alpineUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/"
    private val prefs by lazy { getSharedPreferences("mt_terminal", MODE_PRIVATE) }
    private var busy = false
    private var installProgress: AlertDialog? = null
    private var installPhase: TextView? = null
    private var installLog: TextView? = null
    private var installBar: ProgressBar? = null
    private val installLogBuffer = StringBuilder()
    private var lastProgressLogBytes = 0L
    private lateinit var cards: LinearLayout
    private lateinit var status: TextView

    private data class Distro(val id: String, val name: String, val summary: String, val manager: String, val imagePath: String? = null)
    private val distros = listOf(
        Distro("alpine", "Alpine Linux", "Official ultra-small aarch64 mini root filesystem", "apk"),
        Distro("debian", "Debian Minimal", "Debian Trixie ARM64 container root filesystem", "apt", "debian/trixie/arm64/default"),
        Distro("ubuntu", "Ubuntu Minimal", "Ubuntu Noble ARM64 container root filesystem", "apt", "ubuntu/noble/arm64/default"),
        Distro("arch", "Arch Linux ARM", "Arch Linux ARM64 container root filesystem", "pacman", "archlinux/current/arm64/default"),
        Distro("fedora", "Fedora Minimal", "Fedora 44 ARM64 container root filesystem", "dnf", "fedora/44/arm64/default"),
        Distro("void", "Void Linux", "Void Linux ARM64 container root filesystem", "xbps", "voidlinux/current/arm64/default"),
        Distro("kali", "Kali Linux Minimal", "Kali rolling ARM64 container root filesystem", "apt", "kali/current/arm64/default"),
        Distro("nixos", "NixOS Minimal", "NixOS ARM64 container root filesystem", "nix", "nixos/26.05/arm64/default"),
        Distro("gentoo", "Gentoo Minimal", "Gentoo ARM64 OpenRC container root filesystem", "emerge", "gentoo/current/arm64/openrc"),
        Distro("opensuse", "openSUSE Minimal", "openSUSE Tumbleweed ARM64 container root filesystem", "zypper", "opensuse/tumbleweed/arm64/default"),
        Distro("rocky", "Rocky Linux Minimal", "Rocky Linux 9 ARM64 container root filesystem", "dnf", "rockylinux/9/arm64/default"),
        Distro("openwrt", "OpenWrt Minimal", "OpenWrt ARM64 command-line root filesystem", "opkg", "openwrt/25.12/arm64/default"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildCatalogue()
        val preferred = prefs.getString("active_distro", null)
        Thread {
            val states = distros.associate { it.id to distroState(it.id) }
            val selected = if (preferred != null && states[preferred] == 2) preferred
                else distros.firstOrNull { states[it.id] == 2 }?.id
            runOnUiThread {
                if (selected != null) launchTerminal(selected) else renderCatalogue(states)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun root(id: String) = if (id == "alpine") alpineRoot else "/data/local/mt-os/distros/$id"
    // 0 = not installed, 1 = installed without GNOME Terminal, 2 = GNOME Terminal installed.
    private fun distroState(id: String): Int {
        val p = root(id)
        return try {
            val command = "if [ -x '$p/bin/sh' ] && [ -f '$p/etc/os-release' ] && [ -d '$p/usr/bin' ] && [ -d '$p/var' ]; then if [ -x '$p/usr/bin/gnome-terminal' ] || [ -x '$p/bin/gnome-terminal' ]; then echo 2; else echo 1; fi; else echo 0; fi"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val value = process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 0
            if (process.waitFor() == 0) value else 0
        } catch (_: Exception) { 0 }
    }
    private fun exists(id: String) = distroState(id) > 0
    private fun hasGnomeTerminal(id: String) = distroState(id) == 2

    private fun supportsGnomeTerminal(d: Distro) = d.manager != "opkg"

    private fun buildCatalogue() {
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(17, 23, 36), Color.rgb(31, 42, 61))).apply { cornerRadius = dp(18).toFloat() }
        }
        base.addView(TextView(this).apply {
            text = "GNOME Terminal"; textSize = 22f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        })
        base.addView(TextView(this).apply {
            text = "Launch the real GNOME Terminal package installed inside a Linux distribution. MT-GNOME does not embed a terminal emulator."
            textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(6), 0, dp(10))
        })
        status = TextView(this).apply {
            text = "Checking installed distributions…"; textSize = 11f
            setTextColor(Color.rgb(130, 220, 180)); setPadding(0, dp(4), 0, dp(8))
        }
        base.addView(status)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(cards)
        base.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        base.addView(btn("Close") { finish() }, LinearLayout.LayoutParams(-1, dp(44)))
        setContentView(base)
    }

    private fun showCatalogue() {
        status.text = "Refreshing installed distribution status…"
        Thread {
            val states = distros.associate { it.id to distroState(it.id) }
            runOnUiThread { if (!isFinishing && !isDestroyed) renderCatalogue(states) }
        }.apply { isDaemon = true; start() }
    }

    private fun renderCatalogue(states: Map<String, Int>) {
        if (!::cards.isInitialized) return
        cards.removeAllViews()
        val installedCount = states.values.count { it > 0 }
        distros.forEach { d ->
            val state = states[d.id] ?: 0
            val installed = state > 0
            val ready = state == 2
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(Color.rgb(39, 48, 66), 13)
            }
            val label = when {
                ready -> "GNOME TERMINAL INSTALLED"
                installed && supportsGnomeTerminal(d) -> "INSTALLED • TERMINAL NOT INSTALLED"
                installed -> "INSTALLED • TERMINAL NOT SUPPORTED"
                else -> "DISTRIBUTION AVAILABLE"
            }
            card.addView(TextView(this).apply {
                text = "${d.name}  ·  $label"; textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (ready) Color.rgb(111, 220, 170) else Color.WHITE)
            })
            card.addView(TextView(this).apply {
                text = "${d.summary}\nPackage manager: ${d.manager}"
                textSize = 11f; setTextColor(Color.LTGRAY); setPadding(0, dp(4), 0, dp(8))
            })
            when {
                ready -> card.addView(btn("Open GNOME Terminal") { launchTerminal(d.id) }, LinearLayout.LayoutParams(-1, dp(40)))
                installed && supportsGnomeTerminal(d) -> card.addView(btn("Install GNOME Terminal") { installGnomeTerminal(d) }, LinearLayout.LayoutParams(-1, dp(40)))
                installed -> card.addView(TextView(this).apply {
                    text = "This distribution does not provide a supported GNOME Terminal package."
                    textSize = 11f; setTextColor(Color.rgb(255, 190, 130))
                })
                d.id == "alpine" -> card.addView(btn("Install Alpine Linux") { confirmAlpine() }, LinearLayout.LayoutParams(-1, dp(40)))
                d.imagePath != null -> card.addView(btn("Install distribution") { confirmLxc(d) }, LinearLayout.LayoutParams(-1, dp(40)))
                else -> card.addView(btn("Unavailable") { unavailable(d) }, LinearLayout.LayoutParams(-1, dp(40)))
            }
            cards.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
        status.text = "$installedCount distribution(s) installed. Install GNOME Terminal on an installed distribution."
    }

    private fun launchTerminal(id: String) {
        status.text = "Launching GNOME Terminal…"
        Thread {
            val result = LinuxDistroLauncher.launchGnomeTerminal(id)
            runOnUiThread {
                if (result.first == 0) {
                    prefs.edit().putString("active_distro", id).apply()
                    Toast.makeText(this, "GNOME Terminal launch requested. A compatible X11 or Wayland session is required.", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    showCatalogue()
                    AlertDialog.Builder(this).setTitle("GNOME Terminal could not start")
                        .setMessage(result.second.take(1800).ifBlank {
                            "The application did not remain running. Check that the Linux graphics session and display connection are available."
                        })
                        .setPositiveButton("OK", null).show()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun installGnomeTerminal(d: Distro) {
        val command = when (d.manager) {
            "apk" -> "apk update && apk add gnome-terminal dbus bash"
            "apt" -> "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y gnome-terminal dbus-daemon"
            "pacman" -> "pacman -Sy --noconfirm gnome-terminal dbus"
            "dnf" -> "dnf install -y gnome-terminal dbus-daemon"
            "xbps" -> "xbps-install -Sy gnome-terminal dbus"
            "zypper" -> "zypper --non-interactive refresh && zypper --non-interactive install gnome-terminal dbus-1"
            "nix" -> "nix-env -iA nixpkgs.gnome-terminal"
            "emerge" -> "emerge --sync && emerge --ask=n gnome-base/gnome-terminal sys-apps/dbus"
            else -> null
        }
        if (command == null) {
            Toast.makeText(this, "GNOME Terminal is not supported by this distribution.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Install GNOME Terminal")
            .setMessage("Install the actual GNOME Terminal application and D-Bus support inside the existing ${d.name} root filesystem. This can download substantial graphical dependencies.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install") { _, _ ->
                if (busy) return@setPositiveButton
                busy = true
                showInstallProgress("Installing GNOME Terminal")
                appendInstallLog("Preparing " + d.name + " package installation…")
                appendInstallLog("Command: " + command)
                Thread {
                    updateInstallPhase("Starting package manager…")
                    val result = LinuxDistroLauncher.runCommand(d.id, command, 3600) { line -> appendInstallLog(line) }
                    appendInstallLog("Package manager exited with code " + result.first)
                    if (result.second.isNotBlank() && result.first != 0) appendInstallLog(result.second)
                    updateInstallPhase(if (result.first == 0) "Checking installed GNOME Terminal…" else "Installation failed")
                    val installedOk = result.first == 0 && distroState(d.id) == 2
                    runOnUiThread {
                        busy = false; dismissInstallProgress()
                        if (installedOk) {
                            prefs.edit().putString("active_distro", d.id).apply()
                            showCatalogue()
                            AlertDialog.Builder(this).setTitle("GNOME Terminal installed")
                                .setMessage("The real GNOME Terminal package is installed in ${d.name}. Open it now?")
                                .setNegativeButton("Later", null)
                                .setPositiveButton("Open") { _, _ -> launchTerminal(d.id) }.show()
                        } else {
                            showCatalogue()
                            AlertDialog.Builder(this).setTitle("Installation failed")
                                .setMessage(result.second.take(2200).ifBlank { "The package manager did not install gnome-terminal." })
                                .setPositiveButton("OK", null).show()
                        }
                    }
                }.apply { isDaemon = true; start() }
            }.show()
    }

    private fun setStatus(message: String) { runOnUiThread { if (::status.isInitialized) status.text = message.take(1400) } }

    private fun confirmLxc(d: Distro) {
        AlertDialog.Builder(this).setTitle("Download ${d.name}?")
            .setMessage("Download the latest ARM64 root filesystem from the Linux Containers image server. MT-GNOME verifies the published SHA-256 checksum before extracting it into an isolated directory. This is a minimal container rootfs, not a bootable phone image.")
            .setPositiveButton("Download and install") { _, _ -> installLxc(d) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmAlpine() {
        AlertDialog.Builder(this).setTitle("Download Alpine Linux?")
            .setMessage("Download the official Alpine Linux $alpineVersion aarch64 mini root filesystem and verify its SHA-256 checksum before extraction.")
            .setPositiveButton("Download and install") { _, _ -> installAlpine() }.setNegativeButton("Cancel", null).show()
    }

    private fun installAlpine() {
        if (busy) return
        // Re-check immediately before installing so an existing distro is never downloaded again.
        if (exists("alpine")) {
            prefs.edit().putString("active_distro", "alpine").apply()
            setStatus("\n[Alpine is already installed. Reusing the existing root filesystem; no download needed.]\n")
            showCatalogue()
            return
        }
        busy = true; 
        showInstallProgress("Installing Alpine Linux")
        setStatus("\n[Downloading official Alpine Linux $alpineVersion aarch64 rootfs…]\n")
        Thread {
            var archive: File? = null
            try {
                val url = alpineUrl + archiveName
                updateInstallPhase("Connecting to Alpine download server…")
                val checksumText = fetchText(url + ".sha256")
                val expected = Regex("(?i)\\b[0-9a-f]{64}\\b").find(checksumText)?.value
                    ?: error("Official SHA-256 file did not contain a digest")
                val archiveFile = File(cacheDir, archiveName)
                archive = archiveFile
                download(url, archiveFile) { done, total -> updateInstallProgress("Downloading Alpine", done, total) }
                runOnUiThread { updateInstallPhase("Verifying archive checksum…"); setStatus("[Download complete. Verifying SHA-256…]\n") }
                val actual = sha256(archiveFile)
                if (!actual.equals(expected, true)) { archive.delete(); throw SecurityException("Checksum mismatch; archive deleted.") }
                runOnUiThread { updateInstallPhase("Extracting root filesystem…"); setStatus("[Checksum verified. Extracting minimal root filesystem…]\n") }
                val a = archiveFile.absolutePath
                val script = "set -e; TARGET='$alpineRoot'; STAGING='$alpineRoot.installing'; BACKUP='$alpineRoot.previous'; rm -rf \"\$STAGING\"; mkdir -p \"\$STAGING\"; tar -xzf '$a' -C \"\$STAGING\"; mkdir -p \"\$STAGING/etc/apk\" \"\$STAGING/dev\" \"\$STAGING/proc\" \"\$STAGING/sys\" \"\$STAGING/run\"; printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > \"\$STAGING/etc/resolv.conf\"; printf '%s\\n' 'https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/main' 'https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/community' > \"\$STAGING/etc/apk/repositories\"; test -x \"\$STAGING/bin/sh\" && test -d \"\$STAGING/usr\" && test -d \"\$STAGING/etc\"; printf '%s\\n' 'alpine:$alpineVersion' > \"\$STAGING/.mt-installed\"; rm -rf \"\$BACKUP\"; if [ -e \"\$TARGET\" ]; then mv \"\$TARGET\" \"\$BACKUP\"; fi; if mv \"\$STAGING\" \"\$TARGET\"; then rm -rf \"\$BACKUP\"; else [ ! -e \"\$BACKUP\" ] || mv \"\$BACKUP\" \"\$TARGET\"; exit 1; fi"
                val result = rootExec(script)
                archiveFile.delete()
                if (result.first != 0) error("Root extraction failed: ${result.second}")
                runOnUiThread {
                    prefs.edit().putString("active_distro", "alpine").apply()
                    setStatus("[Alpine installed. Alpine installed successfully.]\n")
                    setStatus("Next: run apk update to refresh package indexes.\n")
                    dismissInstallProgress(); busy = false; showCatalogue()
                }
            } catch (e: Exception) {
                appendInstallLog("ERROR: ${e.message ?: e.toString()}")
                archive?.delete()
                rootExec("rm -rf '$alpineRoot.installing'")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dismissInstallProgress(); setStatus("[Installation failed] ${e.message}\n")
                    setStatus("No distro was selected. Retry from DISTROS.\n")
                    busy = false; 
                }
            }
        }.start()
    }

    private fun fetchText(address: String): String {
        val c = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MT-GNOME/1.0")
        }
        try {
            appendInstallLog("Requesting metadata: $address")
            val response = c.responseCode
            if (response !in 200..299) error("Metadata server HTTP $response")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            appendInstallLog("Metadata received: ${body.length} characters")
            return body
        } catch (e: Exception) {
            appendInstallLog("NETWORK ERROR: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
            throw e
        } finally { c.disconnect() }
    }

    private fun download(address: String, file: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val c = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MT-GNOME/1.0")
        }
        try {
            appendInstallLog("Connecting to: $address")
            updateInstallPhase("Connecting to download server…")
            val response = c.responseCode
            if (response !in 200..299) error("Download server HTTP $response")
            val total = c.contentLengthLong
            appendInstallLog("Server response: HTTP $response")
            appendInstallLog(if (total > 0) "Download size: $total bytes" else "Server did not provide a content length; showing indeterminate progress")
            updateInstallPhase("Connected. Receiving archive…")
            c.inputStream.use { source ->
                file.outputStream().buffered().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReportedBytes = 0L
                    var lastReportedAt = System.currentTimeMillis()
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                        done += count
                        val now = System.currentTimeMillis()
                        if (done - lastReportedBytes >= 256L * 1024L || now - lastReportedAt >= 500L) {
                            onProgress(done, total)
                            lastReportedBytes = done
                            lastReportedAt = now
                        }
                    }
                    onProgress(done, total)
                }
            }
            if (!file.isFile || file.length() == 0L) error("Downloaded archive is empty")
            if (total > 0 && file.length() != total) error("Download ended early: received ${file.length()} of $total bytes")
            appendInstallLog("Archive received successfully: ${file.length()} bytes")
        } catch (e: Exception) {
            appendInstallLog("DOWNLOAD ERROR: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
            throw e
        } finally { c.disconnect() }
    }

    private fun showInstallProgress(title: String) {
        // All callers enter from a button callback on the UI thread. Build the
        // window synchronously before starting the worker so early log/progress
        // events cannot race ahead of the dialog and disappear.
        installLogBuffer.setLength(0)
        lastProgressLogBytes = 0L
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(14))
            setBackgroundColor(Color.rgb(24, 29, 40))
        }
        installPhase = TextView(this).apply { text = "Starting installer…"; textSize = 13f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(8)) }
        installBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0; isIndeterminate = true }
        val logTitle = TextView(this).apply { text = "LIVE INSTALLATION LOG"; textSize = 10f; setTextColor(Color.rgb(130, 190, 255)); setPadding(0, dp(12), 0, dp(5)) }
        installLog = TextView(this).apply { text = "Waiting for installer…"; textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; setTextColor(Color.rgb(205, 216, 232)); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val logScroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = true; addView(installLog) }
        panel.addView(installPhase)
        panel.addView(installBar, LinearLayout.LayoutParams(-1, dp(8)))
        panel.addView(logTitle)
        panel.addView(logScroll, LinearLayout.LayoutParams(-1, dp(230)))
        installProgress = AlertDialog.Builder(this).setTitle(title).setView(panel).setCancelable(false).create()
        installProgress?.show()
        installProgress?.window?.setLayout((resources.displayMetrics.widthPixels * 0.92f).toInt(), -2)
        appendInstallLog("Installer window opened")
    }

    private fun appendInstallLog(message: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val line = message.trim().take(1200)
            if (line.isBlank()) return@runOnUiThread
            installLogBuffer.append(line).append('\n')
            if (installLogBuffer.length > 24000) installLogBuffer.delete(0, installLogBuffer.length - 24000)
            installLog?.text = installLogBuffer.toString()
            installLog?.post { (installLog?.parent as? ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun updateInstallProgress(phase: String, done: Long, total: Long) {
        runOnUiThread {
            installPhase?.text = if (total > 0) "$phase — ${done / 1048576} / ${total / 1048576} MB" else "$phase — ${done / 1048576} MB downloaded"
            installBar?.let { bar ->
                if (total > 0) { bar.isIndeterminate = false; bar.progress = ((done * 100L) / total).toInt().coerceIn(0, 100) }
                else bar.isIndeterminate = true
            }
            if (done - lastProgressLogBytes >= 2097152L || (total > 0 && done >= total)) {
                lastProgressLogBytes = done
                appendInstallLog("$phase: ${done / 1048576} MB received")
            }
        }
    }

    private fun updateInstallPhase(message: String) {
        runOnUiThread { installPhase?.text = message; installBar?.isIndeterminate = true; appendInstallLog(message) }
    }

    private fun dismissInstallProgress() {
        installProgress?.dismiss(); installProgress = null
        installPhase = null; installLog = null; installBar = null
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installLxc(d: Distro) {
        val imagePath = d.imagePath ?: return
        if (busy) return
        // Re-check immediately before downloading in case the catalogue state was stale.
        if (exists(d.id)) {
            prefs.edit().putString("active_distro", d.id).apply()
            setStatus("\n[${d.name} is already installed. Reusing the existing root filesystem; no download needed.]\n")
            showCatalogue()
            return
        }
        busy = true; 
        showInstallProgress("Installing ${d.name}")
        setStatus("\n[Finding latest ARM64 ${d.name} rootfs…]\n")
        Thread {
            var archive: File? = null
            var tarFile: File? = null
            val target = root(d.id)
            val staging = "$target.installing"
            try {
                val base = "https://images.linuxcontainers.org/images/$imagePath/"
                updateInstallPhase("Contacting Linux Containers image server…")
                val listing = fetchText(base)
                updateInstallPhase("Finding the newest published ARM64 image…")
                val stamps = Regex("href=[\\\"'](20[0-9]{6}_[0-9]{2}(?::|%3A)[0-9]{2}/)[\\\"']").findAll(listing).map { it.groupValues[1] }.toList().sorted()
                val stamp = stamps.lastOrNull() ?: error("No published ARM64 build found for this distribution")
                val buildUrl = base + stamp
                updateInstallPhase("Fetching published SHA-256 checksum…")
                val sums = fetchText(buildUrl + "SHA256SUMS")
                val expected = Regex("(?im)^([0-9a-f]{64})\\s+\\*?rootfs\\.tar\\.xz\\s*$").find(sums)?.groupValues?.get(1)
                    ?: error("Published SHA256SUMS does not include rootfs.tar.xz")
                val archiveFile = File(cacheDir, "${d.id}-rootfs.tar.xz")
                val tarOutput = File(cacheDir, "${d.id}-rootfs.tar")
                archive = archiveFile
                tarFile = tarOutput
                runOnUiThread { updateInstallPhase("Downloading ${d.name} rootfs…") }
                download(buildUrl + "rootfs.tar.xz", archiveFile) { done, total -> updateInstallProgress("Downloading ${d.name}", done, total) }
                runOnUiThread { updateInstallPhase("Verifying published SHA-256…"); setStatus("[Verifying published SHA-256…]\n") }
                if (!sha256(archiveFile).equals(expected, true)) throw SecurityException("SHA-256 mismatch; archive rejected")
                runOnUiThread { updateInstallPhase("Checksum verified. Decompressing rootfs…"); setStatus("[Checksum verified. Decompressing rootfs…]\n") }
                XZInputStream(archiveFile.inputStream().buffered()).use { xz ->
                    tarOutput.outputStream().buffered().use { out -> xz.copyTo(out) }
                }
                runOnUiThread { updateInstallPhase("Extracting and preparing Linux filesystem…") }
                val a = tarOutput.absolutePath
                val script = "set -e; TARGET='$target'; STAGING='$staging'; BACKUP=\"\${TARGET}.previous\"; mkdir -p \"\${TARGET%/*}\"; rm -rf \"\$STAGING\" \"\$BACKUP\"; mkdir -p \"\$STAGING\"; tar -xpf '$a' -C \"\$STAGING\"; test -x \"\$STAGING/bin/sh\"; mkdir -p \"\$STAGING/etc\" \"\$STAGING/dev\" \"\$STAGING/proc\" \"\$STAGING/sys\" \"\$STAGING/run\"; rm -f \"\$STAGING/etc/resolv.conf\"; printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > \"\$STAGING/etc/resolv.conf\"; if [ -e \"\$TARGET\" ]; then mv \"\$TARGET\" \"\$BACKUP\"; fi; if mv \"\$STAGING\" \"\$TARGET\"; then rm -rf \"\$BACKUP\"; else [ ! -e \"\$BACKUP\" ] || mv \"\$BACKUP\" \"\$TARGET\"; exit 1; fi; test -x \"\$TARGET/bin/sh\" && test -f \"\$TARGET/etc/os-release\" && test -d \"\$TARGET/usr/bin\" && test -d \"\$TARGET/var\"; printf '%s\\n' '${d.id}' > \"\$TARGET/.mt-installed\""
                val result = rootExec(script)
                if (result.first != 0) error("Root extraction failed: ${result.second}")
                runOnUiThread {
                    prefs.edit().putString("active_distro", d.id).apply()
                    setStatus("[${d.name} installed. Distribution installed successfully.]\n")
                    dismissInstallProgress(); busy = false; showCatalogue()
                }
            } catch (e: Exception) {
                rootExec("rm -rf '$staging'")
                runOnUiThread { dismissInstallProgress(); setStatus("[Installation failed] ${e.message}\n"); setStatus("No distro was selected. Retry from DISTROS.\n"); busy = false }
            } finally { archive?.delete(); tarFile?.delete() }
        }.start()
    }

    private fun unavailable(d: Distro) {
        AlertDialog.Builder(this).setTitle(d.name)
            .setMessage("The official ARM64 minimal rootfs downloader and its verification method are not integrated yet. This distribution is not downloadable in this build; MT-OS will not use an unverified archive.")
            .setPositiveButton("OK", null).show()
    }

    private fun resolveSuExecutable(): String {
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "command -v su"))
        val candidate = process.inputStream.bufferedReader().readText().trim().lineSequence().firstOrNull().orEmpty()
        val exit = process.waitFor()
        if (exit == 0 && candidate.isNotBlank()) {
            if (candidate.startsWith("/")) return candidate
            val path = System.getenv("PATH").orEmpty().split(":")
            path.forEach { dir ->
                val file = File(dir, candidate)
                if (file.isFile && file.canExecute()) return file.absolutePath
            }
        }
        // Common Magisk/Kernelsu locations are checked as a fallback; never silently
        // start a shell that will immediately fail with "su: not found".
        val candidates = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/debug_ramdisk/su", "/data/adb/magisk/su",
            "/data/adb/ksu/bin/su"
        )
        return candidates.firstOrNull { File(it).canExecute() }
            ?: throw IllegalStateException("Root executable not found. Open Magisk, grant MT-GNOME superuser access, then retry. App PATH: ${System.getenv("PATH")}")
    }

    private fun rootExec(script: String, timeoutSeconds: Long = 90): Pair<Int, String> {
        val p = Runtime.getRuntime().exec(arrayOf(resolveSuExecutable(), "-c", "exec 2>&1; $script"))
        val out = StringBuilder()
        val reader = Thread {
            try {
                BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) synchronized(out) { out.append(line).append('\n') }
                }
            } catch (e: Exception) {
                android.util.Log.w("MT-GNOME", "Root command output reader stopped", e)
            }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroy()
            if (p.isAlive) p.destroyForcibly()
            reader.join(500)
            return Pair(124, "Root command timed out after $timeoutSeconds seconds")
        }
        reader.join(1500)
        return Pair(p.exitValue(), synchronized(out) { out.toString() })
    }

    private fun btn(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; isAllCaps = false
        setTextColor(Color.WHITE); background = rounded(Color.rgb(59, 87, 126), 10)
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
