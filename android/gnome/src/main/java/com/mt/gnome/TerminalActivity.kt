package com.mt.gnome

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.tukaani.xz.XZInputStream

/** Minimal ARM64 Linux catalogue and terminal. No desktop environments are installed. */
class TerminalActivity : Activity() {
    private val alpineRoot = "/data/local/mt-os/distros/alpine"
    private val alpineVersion = "3.24.4"
    private val archiveName = "alpine-minirootfs-$alpineVersion-aarch64.tar.gz"
    private val alpineUrl = "https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/releases/aarch64/"
    private val prefs by lazy { getSharedPreferences("mt_terminal", MODE_PRIVATE) }
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private var busy = false
    private var active: String? = null
    private var installProgress: ProgressDialog? = null
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = 0

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
        active = prefs.getString("active_distro", null)
        requestedOrientation = when (getSharedPreferences("mt_gnome", MODE_PRIVATE).getString("orientation", "landscape")) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "system" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        buildUi()
        val id = active
        if (id != null && exists(id)) append("Terminal ready — ${name(id)}. Type help.\n")
        else { active = null; showCatalogue() }
    }

    private fun name(id: String) = distros.firstOrNull { it.id == id }?.name ?: id
    private fun root(id: String) = when (id) { "alpine" -> alpineRoot; else -> "/data/local/mt-os/distros/$id" }
    private fun exists(id: String): Boolean {
        val p = root(id)
        if (p.isEmpty()) return false
        return try { Runtime.getRuntime().exec(arrayOf("su", "-c", "test -x '$p/bin/sh'")).waitFor() == 0 } catch (_: Exception) { false }
    }

    private fun buildUi() {
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.rgb(18, 22, 31)
        window.navigationBarColor = Color.rgb(18, 22, 31)
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(Color.rgb(17, 20, 29))
        }
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(4), 0)
            background = rounded(Color.rgb(31, 36, 49), 10)
        }
        titleBar.addView(TextView(this).apply {
            text = "●  ●  ●"; textSize = 12f; setTextColor(Color.rgb(231, 133, 137))
            setPadding(dp(8), 0, dp(12), 0)
        })
        titleBar.addView(TextView(this).apply {
            text = "Terminal"; textSize = 14f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        titleBar.addView(btn("⋮") { showTerminalMenu() })
        base.addView(titleBar)
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        toolbar.addView(btn("Distros") { showCatalogue() })
        toolbar.addView(btn("Clear") { output.text = "" })
        toolbar.addView(btn("Help") { append("\nCommands: help, clear, exit. Install tools with your distro package manager.\n") })
        base.addView(toolbar)
        base.addView(TextView(this).apply {
            text = "LINUX SHELL  •  ARM64"
            textSize = 10f; letterSpacing = 0.08f
            setTextColor(Color.rgb(113, 205, 170))
            setPadding(dp(4), dp(3), 0, dp(5))
        })
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            scrollbarStyle = View.SCROLLBARS_INSIDE_INSET
            background = rounded(Color.rgb(8, 11, 17), 9)
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        output = TextView(this).apply {
            text = "MT Terminal  •  ready\n"; textSize = 13f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(Color.rgb(218, 228, 240))
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1.04f)
        }
        scroll.addView(output)
        base.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        val prompt = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(5), 0)
            background = rounded(Color.rgb(29, 35, 48), 9)
        }
        prompt.addView(TextView(this).apply {
            text = "❯"; textSize = 18f; setTextColor(Color.rgb(111, 220, 170))
            setPadding(dp(5), 0, dp(7), 0)
        })
        input = EditText(this).apply {
            hint = "Run a command…"; setHintTextColor(Color.rgb(125, 135, 153)); setTextColor(Color.WHITE)
            textSize = 13f; typeface = Typeface.MONOSPACE
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(3), 0, dp(3), 0); background = null
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_NULL) { runCommand(); true } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && commandHistory.isNotEmpty()) {
                    historyIndex = (historyIndex - 1).coerceAtLeast(0)
                    setText(commandHistory[historyIndex]); setSelection(text.length); true
                } else if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN && commandHistory.isNotEmpty()) {
                    historyIndex = (historyIndex + 1).coerceAtMost(commandHistory.size)
                    setText(if (historyIndex == commandHistory.size) "" else commandHistory[historyIndex])
                    setSelection(text.length); true
                } else false
            }
        }
        prompt.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        prompt.addView(btn("Run") { runCommand() }.apply {
            setTextColor(Color.WHITE); background = rounded(Color.rgb(87, 105, 190), 8)
        }, LinearLayout.LayoutParams(dp(64), dp(40)))
        base.addView(prompt, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setContentView(base)
    }

    private fun showTerminalMenu() {
        AlertDialog.Builder(this).setTitle("Terminal")
            .setItems(arrayOf("Choose distribution", "Clear terminal", "Terminal help", "Close terminal")) { _, which ->
                when (which) {
                    0 -> showCatalogue()
                    1 -> output.text = ""
                    2 -> append("\nCommands: help, clear, exit. Install tools with apk, apt, dnf, pacman, etc.\n")
                    3 -> finish()
                }
            }.show()
    }

    private fun showCatalogue() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        panel.addView(TextView(this).apply { text = "Choose your Linux distribution"; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        panel.addView(TextView(this).apply {
            text = "First launch: choose a minimal ARM64 Linux system to download. After installation, MT Terminal opens that distribution automatically. All systems are command-line-only: no desktop environments or graphical bundles."
            textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(6), 0, dp(10))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val inner = ScrollView(this)
        distros.forEach { d ->
            val installed = exists(d.id)
            val state = when { installed -> "INSTALLED • OPEN"; d.id == "alpine" -> "OFFICIAL DOWNLOAD"; else -> "ARM64 DOWNLOAD" }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(Color.rgb(34, 42, 58), 12)
                setOnClickListener {
                    when { installed -> select(d.id); d.id == "alpine" -> confirmAlpine(); else -> confirmLxc(d) }
                }
            }
            card.addView(TextView(this).apply { text = "${d.name}   ·   $state"; textSize = 14f
                setTextColor(if (installed || d.id == "alpine") Color.rgb(111, 220, 170) else Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
            card.addView(TextView(this).apply { text = "${d.summary}\nPackage manager: ${d.manager}"; textSize = 11f; setTextColor(Color.LTGRAY); setPadding(0, dp(4), 0, 0) })
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        inner.addView(list); panel.addView(inner, LinearLayout.LayoutParams(-1, dp(365)))
        AlertDialog.Builder(this).setView(panel).setNegativeButton("Close", null).show()
    }

    private fun confirmAlpine() {
        AlertDialog.Builder(this).setTitle("Download Alpine Linux?")
            .setMessage("Download the official Alpine Linux $alpineVersion aarch64 mini root filesystem and its SHA-256 checksum from dl-cdn.alpinelinux.org. MT Terminal verifies the archive before extracting it into its own directory. No desktop environment is installed.")
            .setPositiveButton("Download and install") { _, _ -> installAlpine() }.setNegativeButton("Cancel", null).show()
    }

    private fun installAlpine() {
        if (busy) return
        busy = true; input.isEnabled = false
        showInstallProgress("Installing Alpine Linux")
        append("\n[Downloading official Alpine Linux $alpineVersion aarch64 rootfs…]\n")
        Thread {
            var archive: File? = null
            try {
                val url = alpineUrl + archiveName
                val checksumText = fetchText(url + ".sha256")
                val expected = Regex("(?i)\\b[0-9a-f]{64}\\b").find(checksumText)?.value
                    ?: error("Official SHA-256 file did not contain a digest")
                val archiveFile = File(cacheDir, archiveName)
                archive = archiveFile
                download(url, archiveFile) { done, total -> updateInstallProgress("Downloading Alpine", done, total) }
                runOnUiThread { updateInstallPhase("Verifying archive checksum…"); append("[Download complete. Verifying SHA-256…]\n") }
                val actual = sha256(archiveFile)
                if (!actual.equals(expected, true)) { archive.delete(); throw SecurityException("Checksum mismatch; archive deleted.") }
                runOnUiThread { updateInstallPhase("Extracting root filesystem…"); append("[Checksum verified. Extracting minimal root filesystem…]\n") }
                val a = archiveFile.absolutePath
                val script = "set -e; mkdir -p '$alpineRoot'; tar -xzf '$a' -C '$alpineRoot'; mkdir -p '$alpineRoot/dev' '$alpineRoot/proc' '$alpineRoot/sys' '$alpineRoot/run'; printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > '$alpineRoot/etc/resolv.conf'; printf '%s\\n' 'https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/main' 'https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/community' > '$alpineRoot/etc/apk/repositories'; test -x '$alpineRoot/bin/sh'"
                val result = rootExec(script)
                archiveFile.delete()
                if (result.first != 0) error("Root extraction failed: ${result.second}")
                runOnUiThread {
                    active = "alpine"; prefs.edit().putString("active_distro", "alpine").apply()
                    append("[Alpine installed. Opening Alpine terminal…]\n")
                    append("Next: run apk update to refresh package indexes.\n")
                    dismissInstallProgress(); busy = false; input.isEnabled = true; runLinux("cat /etc/os-release; id")
                }
            } catch (e: Exception) {
                archive?.delete()
                runOnUiThread { dismissInstallProgress(); append("[Installation failed] ${e.message}\n"); append("No distro was selected. Retry from DISTROS.\n"); busy = false; input.isEnabled = true }
            }
        }.start()
    }

    private fun fetchText(address: String): String {
        val c = (URL(address).openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true }
        try { if (c.responseCode !in 200..299) error("Checksum server HTTP ${c.responseCode}"); return c.inputStream.bufferedReader().use { it.readText() } }
        finally { c.disconnect() }
    }

    private fun download(address: String, file: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val c = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true
        }
        try {
            if (c.responseCode !in 200..299) error("Download server HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { source ->
                file.outputStream().buffered().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                        done += count
                        onProgress(done, total)
                    }
                }
            }
            if (!file.isFile || file.length() == 0L) error("Downloaded archive is empty")
            if (total > 0 && file.length() != total) error("Download ended early: received ${file.length()} of $total bytes")
        } finally { c.disconnect() }
    }

    private fun showInstallProgress(title: String) {
        runOnUiThread {
            installProgress = ProgressDialog(this).apply {
                setTitle(title)
                setMessage("Preparing download…")
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                max = 100
                progress = 0
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                show()
            }
        }
    }

    private fun updateInstallProgress(phase: String, done: Long, total: Long) {
        runOnUiThread {
            installProgress?.let { dialog ->
                dialog.setMessage(if (total > 0) "$phase — ${done / 1048576} / ${total / 1048576} MB" else "$phase — ${done / 1048576} MB")
                if (total > 0) { dialog.isIndeterminate = false; dialog.progress = ((done * 100L) / total).toInt().coerceIn(0, 100) }
                else dialog.isIndeterminate = true
            }
        }
    }

    private fun updateInstallPhase(message: String) {
        runOnUiThread { installProgress?.let { it.isIndeterminate = true; it.setMessage(message) } }
    }

    private fun dismissInstallProgress() {
        installProgress?.dismiss()
        installProgress = null
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun select(id: String) {
        active = id; prefs.edit().putString("active_distro", id).apply()
        append("\n[Switched to ${name(id)}]\n"); runLinux("cat /etc/os-release; id")
    }

    private fun confirmLxc(d: Distro) {
        AlertDialog.Builder(this).setTitle("Download ${d.name}?")
            .setMessage("Download the latest ARM64 root filesystem from the Linux Containers image server. MT Terminal verifies the published SHA-256 checksum before extracting it into an isolated directory. This is a minimal command-line container rootfs, not a bootable phone image. No desktop environment is installed.")
            .setPositiveButton("Download and install") { _, _ -> installLxc(d) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun installLxc(d: Distro) {
        val imagePath = d.imagePath ?: return
        if (busy) return
        busy = true; input.isEnabled = false
        showInstallProgress("Installing ${d.name}")
        append("\n[Finding latest ARM64 ${d.name} rootfs…]\n")
        Thread {
            var archive: File? = null
            var tarFile: File? = null
            val target = root(d.id)
            val staging = "$target.installing"
            try {
                val base = "https://images.linuxcontainers.org/images/$imagePath/"
                val listing = fetchText(base)
                val stamps = Regex("href=[\\\"'](20[0-9]{6}_[0-9]{2}(?::|%3A)[0-9]{2}/)[\\\"']").findAll(listing).map { it.groupValues[1] }.toList().sorted()
                val stamp = stamps.lastOrNull() ?: error("No published ARM64 build found for this distribution")
                val buildUrl = base + stamp
                val sums = fetchText(buildUrl + "SHA256SUMS")
                val expected = Regex("(?im)^([0-9a-f]{64})\\s+\\*?rootfs\\.tar\\.xz\\s*$").find(sums)?.groupValues?.get(1)
                    ?: error("Published SHA256SUMS does not include rootfs.tar.xz")
                val archiveFile = File(cacheDir, "${d.id}-rootfs.tar.xz")
                val tarOutput = File(cacheDir, "${d.id}-rootfs.tar")
                archive = archiveFile
                tarFile = tarOutput
                runOnUiThread { updateInstallPhase("Downloading ${d.name} rootfs…") }
                download(buildUrl + "rootfs.tar.xz", archiveFile) { done, total -> updateInstallProgress("Downloading ${d.name}", done, total) }
                runOnUiThread { updateInstallPhase("Verifying published SHA-256…"); append("[Verifying published SHA-256…]\n") }
                if (!sha256(archiveFile).equals(expected, true)) throw SecurityException("SHA-256 mismatch; archive rejected")
                runOnUiThread { updateInstallPhase("Checksum verified. Decompressing rootfs…"); append("[Checksum verified. Decompressing rootfs…]\n") }
                XZInputStream(archiveFile.inputStream().buffered()).use { xz ->
                    tarOutput.outputStream().buffered().use { out -> xz.copyTo(out) }
                }
                runOnUiThread { updateInstallPhase("Extracting and preparing Linux filesystem…") }
                val a = tarOutput.absolutePath
                val script = "set -e; rm -rf '$staging'; mkdir -p '$staging'; tar -xpf '$a' -C '$staging'; test -x '$staging/bin/sh'; mkdir -p '$staging/dev' '$staging/proc' '$staging/sys' '$staging/run'; if [ -e '$staging/etc/resolv.conf' ]; then rm -f '$staging/etc/resolv.conf'; fi; printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > '$staging/etc/resolv.conf'; rm -rf '$target'; mv '$staging' '$target'; test -x '$target/bin/sh'"
                val result = rootExec(script)
                if (result.first != 0) error("Root extraction failed: ${result.second}")
                runOnUiThread {
                    active = d.id; prefs.edit().putString("active_distro", d.id).apply()
                    append("[${d.name} installed. Opening terminal…]\n")
                    dismissInstallProgress(); busy = false; input.isEnabled = true; runLinux("cat /etc/os-release; id")
                }
            } catch (e: Exception) {
                rootExec("rm -rf '$staging'")
                runOnUiThread { dismissInstallProgress(); append("[Installation failed] ${e.message}\n"); append("No distro was selected. Retry from DISTROS.\n"); busy = false; input.isEnabled = true }
            } finally { archive?.delete(); tarFile?.delete() }
        }.start()
    }

    private fun unavailable(d: Distro) {
        AlertDialog.Builder(this).setTitle(d.name)
            .setMessage("The official ARM64 minimal rootfs downloader and its verification method are not integrated yet. This distribution is not downloadable in this build; MT-OS will not use an unverified archive.")
            .setPositiveButton("OK", null).show()
    }

    private fun runCommand() {
        if (busy) return
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return
        input.text.clear(); commandHistory.add(cmd); historyIndex = commandHistory.size; append("\n\$ $cmd\n")
        if (cmd == "help") { append("Run commands inside the selected minimal Linux distribution. Alpine: apk update, apk add <package>. Common: pwd, ls, uname -a, df -h, free -h, whoami, exit. No desktop installer.\n"); return }
        if (cmd == "clear") { output.text = ""; return }
        if (cmd == "exit") { finish(); return }
        runLinux(cmd)
    }

    private fun runLinux(command: String) {
        val id = active ?: run { showCatalogue(); return }
        val r = root(id); if (r.isEmpty() || busy) return
        busy = true; input.isEnabled = false
        Thread {
            try {
                val q = "'" + command.replace("'", "'\\''") + "'"
                val script = "R='$r'; test -x \"\$R/bin/sh\" || exit 20; mkdir -p \"\$R/dev\" \"\$R/proc\" \"\$R/sys\"; mountpoint -q \"\$R/dev\" || mount --bind /dev \"\$R/dev\"; mountpoint -q \"\$R/proc\" || mount -t proc proc \"\$R/proc\"; mountpoint -q \"\$R/sys\" || mount -t sysfs sysfs \"\$R/sys\"; chroot \"\$R\" /bin/sh -lc $q 2>&1; code=\$?; umount \"\$R/sys\" 2>/dev/null || true; umount \"\$R/proc\" 2>/dev/null || true; umount \"\$R/dev\" 2>/dev/null || true; exit \$code"
                val result = rootExec(script)
                runOnUiThread { if (result.second.isNotBlank()) append(result.second); if (!result.second.endsWith("\n")) append("\n"); append("[process exited: ${result.first}]\n") }
            } catch (e: Exception) { runOnUiThread { append("[terminal error] ${e.message}\n") } }
            finally { runOnUiThread { busy = false; input.isEnabled = true; input.requestFocus() } }
        }.start()
    }

    private fun rootExec(script: String): Pair<Int, String> {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
        val out = StringBuilder()
        BufferedReader(InputStreamReader(p.inputStream)).use { r -> var line: String?; while (r.readLine().also { line = it } != null) out.append(line).append('\n') }
        return Pair(p.waitFor(), out.toString())
    }

    private fun append(s: String) { output.append(s); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
    private fun btn(label: String, action: () -> Unit) = Button(this).apply { text = label; textSize = 10f; isAllCaps = false; setOnClickListener { action() } }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
