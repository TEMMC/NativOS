package com.mt.gnome

import android.app.Activity
import android.app.AlertDialog
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
import java.security.MessageDigest\nimport org.tukaani.xz.XZInputStream

/** Minimal ARM64 Linux catalogue and terminal. No desktop environments are installed. */
class TerminalActivity : Activity() {
    private val alpineRoot = "/data/local/mt-os/distros/alpine"
    private val ubuntuRoot = "/data/user/0/com.mt.os/files/rootfs"
    private val alpineVersion = "3.24.4"
    private val archiveName = "alpine-minirootfs-$alpineVersion-aarch64.tar.gz"
    private val alpineUrl = "https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/releases/aarch64/"
    private val prefs by lazy { getSharedPreferences("mt_terminal", MODE_PRIVATE) }
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private var busy = false
    private var active: String? = null

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
        Distro("mt-ubuntu", "Existing MT-OS Ubuntu", "Existing MT-OS Ubuntu environment with Phosh/Phoc preserved", "apt")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = prefs.getString("active_distro", null)
        buildUi()
        val id = active
        if (id != null && exists(id)) append("Terminal ready — ${name(id)}. Type help.\n")
        else { active = null; showCatalogue() }
    }

    private fun name(id: String) = distros.firstOrNull { it.id == id }?.name ?: id
    private fun root(id: String) = when (id) { "alpine" -> alpineRoot; "mt-ubuntu" -> ubuntuRoot; else -> "/data/local/mt-os/distros/$id" }
    private fun exists(id: String): Boolean {
        val p = root(id)
        if (p.isEmpty()) return false
        return try { Runtime.getRuntime().exec(arrayOf("su", "-c", "test -x '$p/bin/sh'")).waitFor() == 0 } catch (_: Exception) { false }
    }

    private fun buildUi() {
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(13, 17, 25))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply {
            text = "MT-OS  /  TERMINAL"; textSize = 16f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        bar.addView(btn("DISTROS") { showCatalogue() })
        bar.addView(btn("CLEAR") { output.text = "" })
        base.addView(bar)
        base.addView(TextView(this).apply {
            text = "MINIMAL LINUX  •  COMMAND-LINE ONLY"; textSize = 10f
            setTextColor(Color.rgb(111, 194, 168)); setPadding(0, 0, 0, dp(10))
        })
        scroll = ScrollView(this).apply {
            isFillViewport = true; background = rounded(Color.rgb(7, 11, 17), 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        output = TextView(this).apply {
            text = "MT-OS Terminal [0.4]\n"; textSize = 12f; typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(218, 229, 239)); setTextIsSelectable(true)
        }
        scroll.addView(output)
        base.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        input = EditText(this).apply {
            hint = "Enter a Linux command…"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            textSize = 13f; singleLine = true; imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12), 0, dp(12), 0); background = rounded(Color.rgb(30, 38, 51), 10)
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { runCommand(); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(btn("RUN") { runCommand() }.apply { setTextColor(Color.WHITE); background = rounded(Color.rgb(92, 75, 180), 10) },
            LinearLayout.LayoutParams(dp(72), dp(46)).apply { leftMargin = dp(8) })
        base.addView(row)
        setContentView(base)
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
            val state = when { installed -> "INSTALLED • OPEN"; d.id == "alpine" -> "OFFICIAL DOWNLOAD"; d.id == "mt-ubuntu" -> "EXISTING MT-OS"; else -> "ARM64 DOWNLOAD" }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(Color.rgb(34, 42, 58), 12)
                setOnClickListener {
                    when { installed -> select(d.id); d.id == "alpine" -> confirmAlpine(); d.id == "mt-ubuntu" -> selectUbuntu(); else -> confirmLxc(d) }
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
        append("\n[Downloading official Alpine Linux $alpineVersion aarch64 rootfs…]\n")
        Thread {
            var archive: File? = null
            try {
                val url = alpineUrl + archiveName
                val checksumText = fetchText(url + ".sha256")
                val expected = Regex("(?i)\\b[0-9a-f]{64}\\b").find(checksumText)?.value
                    ?: error("Official SHA-256 file did not contain a digest")
                archive = File(cacheDir, archiveName)
                download(url, archive)
                runOnUiThread { append("[Download complete. Verifying SHA-256…]\n") }
                val actual = sha256(archive)
                if (!actual.equals(expected, true)) { archive.delete(); throw SecurityException("Checksum mismatch; archive deleted.") }
                runOnUiThread { append("[Checksum verified. Extracting minimal root filesystem…]\n") }
                val a = archive.absolutePath
                val script = "set -e; mkdir -p '$alpineRoot'; tar -xzf '$a' -C '$alpineRoot'; mkdir -p '$alpineRoot/dev' '$alpineRoot/proc' '$alpineRoot/sys' '$alpineRoot/run'; printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > '$alpineRoot/etc/resolv.conf'; printf '%s\\n' 'https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/main' 'https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/community' > '$alpineRoot/etc/apk/repositories'; test -x '$alpineRoot/bin/sh'"
                val result = rootExec(script)
                archive.delete()
                if (result.first != 0) error("Root extraction failed: ${result.second}")
                runOnUiThread {
                    active = "alpine"; prefs.edit().putString("active_distro", "alpine").apply()
                    append("[Alpine installed. Opening Alpine terminal…]\n")
                    append("Next: run apk update to refresh package indexes.\n")
                    busy = false; input.isEnabled = true; runLinux("cat /etc/os-release; id")
                }
            } catch (e: Exception) {
                archive?.delete()
                runOnUiThread { append("[Installation failed] ${e.message}\n"); append("No distro was selected. Retry from DISTROS.\n"); busy = false; input.isEnabled = true }
            }
        }.start()
    }

    private fun fetchText(address: String): String {
        val c = (URL(address).openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true }
        try { if (c.responseCode !in 200..299) error("Checksum server HTTP ${c.responseCode}"); return c.inputStream.bufferedReader().use { it.readText() } }
        finally { c.disconnect() }
    }

    private fun download(address: String, file: File) {
        val c = (URL(address).openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true }
        try {
            if (c.responseCode !in 200..299) error("Download server HTTP ${c.responseCode}")
            c.inputStream.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
            if (!file.isFile || file.length() == 0L) error("Downloaded archive is empty")
        } finally { c.disconnect() }
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
                archive = File(cacheDir, "${d.id}-rootfs.tar.xz")
                tarFile = File(cacheDir, "${d.id}-rootfs.tar")
                download(buildUrl + "rootfs.tar.xz", archive)
                runOnUiThread { append("[Verifying published SHA-256…]\n") }
                if (!sha256(archive).equals(expected, true)) throw SecurityException("SHA-256 mismatch; archive rejected")
                runOnUiThread { append("[Checksum verified. Decompressing rootfs…]\n") }
                XZInputStream(archive.inputStream().buffered()).use { xz ->
                    tarFile.outputStream().buffered().use { out -> xz.copyTo(out) }
                }
                val a = tarFile.absolutePath
                val script = "set -e; rm -rf '$staging'; mkdir -p '$staging'; tar -xpf '$a' -C '$staging'; test -x '$staging/bin/sh'; mkdir -p '$staging/dev' '$staging/proc' '$staging/sys' '$staging/run'; if [ -e '$staging/etc/resolv.conf' ]; then rm -f '$staging/etc/resolv.conf'; fi; printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > '$staging/etc/resolv.conf'; rm -rf '$target'; mv '$staging' '$target'; test -x '$target/bin/sh'"
                val result = rootExec(script)
                if (result.first != 0) error("Root extraction failed: ${result.second}")
                runOnUiThread {
                    active = d.id; prefs.edit().putString("active_distro", d.id).apply()
                    append("[${d.name} installed. Opening terminal…]\n")
                    busy = false; input.isEnabled = true; runLinux("cat /etc/os-release; id")
                }
            } catch (e: Exception) {
                rootExec("rm -rf '$staging'")
                runOnUiThread { append("[Installation failed] ${e.message}\n"); append("No distro was selected. Retry from DISTROS.\n"); busy = false; input.isEnabled = true }
            } finally { archive?.delete(); tarFile?.delete() }
        }.start()
    }

    private fun selectUbuntu() {
        AlertDialog.Builder(this).setTitle("Use existing MT-OS Ubuntu?")
            .setMessage("Open the existing MT-OS Ubuntu root filesystem without replacing its installed Phosh/Phoc environment.")
            .setPositiveButton("Open Ubuntu") { _, _ -> if (exists("mt-ubuntu")) select("mt-ubuntu") else Toast.makeText(this, "Existing Ubuntu rootfs not found.", Toast.LENGTH_LONG).show() }
            .setNegativeButton("Cancel", null).show()
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
        input.text.clear(); append("\n\$ $cmd\n")
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
