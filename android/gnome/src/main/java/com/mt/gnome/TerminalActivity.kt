package com.mt.gnome

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.text.Editable
import android.text.Spannable
import android.text.Selection
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
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
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView

/** Minimal ARM64 Linux catalogue and terminal. No desktop environments are installed. */
class TerminalActivity : Activity() {
    private val alpineRoot = "/data/local/mt-os/distros/alpine"
    private val alpineVersion = "3.24.4"
    private val archiveName = "alpine-minirootfs-$alpineVersion-aarch64.tar.gz"
    private val alpineUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/"
    private val prefs by lazy { getSharedPreferences("mt_terminal", MODE_PRIVATE) }
    private lateinit var output: TextView
    private lateinit var terminalView: TerminalView
    private lateinit var terminalBridge: MtTerminalBridge
    private var ctrlButton: Button? = null
    private lateinit var scroll: FrameLayout
    private var busy = false
    private var active: String? = null
    private var installProgress: ProgressDialog? = null
    private var windowX = 0
    private var windowY = 0
    private var windowWidth = 0
    private var windowHeight = 0
    private var maximized = false
    @Volatile private var closingWindow = false
    private var restoreBounds = intArrayOf(0, 0, 0, 0)
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = 0
    private data class ShellSession(
        val number: Int, val distroId: String, val buffer: StringBuilder = StringBuilder(),
        var starting: Boolean = true,
        @Volatile var closing: Boolean = false,
        @Volatile var exited: Boolean = false,
        @Volatile var vtSession: TerminalSession? = null
    )
    private val sessions = mutableListOf<ShellSession>()
    private var activeSession: ShellSession? = null
    private var sessionCounter = 0
    private val sessionLock = Any()
    private val pendingShutdownByDistro = mutableMapOf<String, Int>()
    @Volatile private var activityDestroyed = false
    private lateinit var sessionTabs: LinearLayout

    companion object {
        @Volatile var overlayContent: View? = null
        @Volatile var hostActivity: TerminalActivity? = null
    }

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
        hostActivity = this
        // Termux's VT renderer paints its own default background; changing only the
        // Android View background leaves an opaque black terminal. Use an alpha
        // background in the emulator colour scheme so the wallpaper shows through.
        val terminalOpacity = getSharedPreferences("mt_gnome", MODE_PRIVATE)
            .getInt("terminal_opacity", 30).coerceIn(10, 90)
        TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] =
            Color.argb((terminalOpacity * 255 / 100), 7, 10, 18)
        TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR] = Color.WHITE
        requestedOrientation = when (getSharedPreferences("mt_gnome", MODE_PRIVATE).getString("orientation", "landscape")) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "system" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        buildUi()
        windowWidth = (resources.displayMetrics.widthPixels * 0.94f).toInt()
        windowHeight = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        windowX = (resources.displayMetrics.widthPixels - windowWidth) / 2
        windowY = (resources.displayMetrics.heightPixels - windowHeight) / 2
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        applyWindowBounds()
        val id = active
        if (id != null && exists(id)) startSession(id)
        else { active = null; showCatalogue() }
    }

    private fun name(id: String) = distros.firstOrNull { it.id == id }?.name ?: id
    private fun root(id: String) = when (id) { "alpine" -> alpineRoot; else -> "/data/local/mt-os/distros/$id" }
    private fun exists(id: String): Boolean {
        val p = root(id)
        if (p.isEmpty()) return false
        // Treat a distro as installed only when its core rootfs layout is present.
        // This also recognises valid installs created by older app versions.
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "test -x '$p/bin/sh' && test -f '$p/etc/os-release' && test -d '$p/usr/bin' && test -d '$p/var'")).waitFor() == 0
        } catch (_: Exception) { false }
    }

    private fun buildUi() {
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.rgb(18, 22, 31)
        window.navigationBarColor = Color.rgb(18, 22, 31)
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = rounded(Color.argb(76, 17, 20, 29), 16)
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
        val title = TextView(this).apply {
            text = "MT Terminal"; textSize = 14f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { dragStart(event.rawX, event.rawY); true }
                    MotionEvent.ACTION_MOVE -> { dragWindow(event.rawX, event.rawY); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        titleBar.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        titleBar.addView(btn("—") { moveTaskToBack(true) })
        titleBar.addView(btn("□") { toggleMaximize() })
        titleBar.addView(btn("×") { closeTerminalWindow() })
        val menuButton = btn("⋮") {}
        menuButton.setOnClickListener { showTerminalMenu(menuButton) }
        titleBar.addView(menuButton)
        base.addView(titleBar)
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(4))
        }
        toolbar.addView(btn("Distros") { showCatalogue() })
        toolbar.addView(btn("New +") { newSession() })
        toolbar.addView(btn("Select") { selectAllTerminalOutput() })
        toolbar.addView(btn("Copy") { copyTerminalSelection() })
        toolbar.addView(btn("Paste") { pasteClipboard() })
        toolbar.addView(btn("Clear") { clearActiveTerminal() })
        toolbar.addView(btn("Ctrl") { terminalBridge.toggleControlKey(); renderControlButton() }.also { ctrlButton = it })
        toolbar.addView(btn("Ctrl+D") { sendPty("\u0004") })
        toolbar.addView(btn("Tab") { sendPty("\t") })
        toolbar.addView(btn("Esc") { sendPty("\u001b") })
        base.addView(toolbar)
        sessionTabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(4)) }
        base.addView(sessionTabs, LinearLayout.LayoutParams(-1, dp(42)))
        renderSessionTabs()
        base.addView(TextView(this).apply {
            text = "LINUX SHELL  •  ARM64"
            textSize = 10f; letterSpacing = 0.08f
            setTextColor(Color.rgb(113, 205, 170))
            setPadding(dp(4), dp(3), 0, dp(5))
        })
        // Initialise the status view before attaching or configuring it. The old order
        // dereferenced this lateinit property before assignment on a cold launch.
        output = TextView(this).apply {
            text = "MT Terminal  •  select an installed distribution"
            textSize = 10f
            setTextColor(Color.rgb(150, 205, 190))
            maxLines = 2
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(dp(4), dp(2), 0, dp(2))
        }
        base.addView(output, LinearLayout.LayoutParams(-1, dp(24)))
        scroll = FrameLayout(this).apply {
            isFocusable = false
            background = rounded(Color.argb(18, 8, 11, 17), 9)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        terminalView = TerminalView(this, null).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        terminalBridge = MtTerminalBridge(this, terminalView)
        renderControlButton()
        scroll.addView(terminalView, FrameLayout.LayoutParams(-1, -1))
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> resizePty() }
        base.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })

        terminalView.setOnClickListener { focusTerminalInput() }
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), dp(2), 0)
        }
        bottomBar.addView(TextView(this).apply {
            text = "⤡"; textSize = 22f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Drag to resize terminal window"
            background = rounded(Color.argb(190, 65, 78, 104), 8)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { resizeStart(event.rawX, event.rawY); true }
                    MotionEvent.ACTION_MOVE -> { resizeWindow(event.rawX, event.rawY); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }, LinearLayout.LayoutParams(dp(68), dp(40)))
        base.addView(bottomBar, LinearLayout.LayoutParams(-1, dp(44)))
        setContentView(base)
        overlayContent = base
        if (android.provider.Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingTerminalService::class.java))
        } else {
            AlertDialog.Builder(this).setTitle("Allow floating terminal")
                .setMessage("MT Terminal needs Display over other apps permission so it can stay visible while you use other Android apps.")
                .setPositiveButton("Grant permission") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Use normal window", null).show()
        }
    }

    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureWindowX = 0
    private var gestureWindowY = 0
    private var gestureWindowWidth = 0
    private var gestureWindowHeight = 0

    private fun applyWindowBounds() {
        // The terminal is a TYPE_APPLICATION_OVERLAY window, not the Activity
        // window. Update the WindowManager.LayoutParams that own the visible view.
        FloatingTerminalService.updateBounds(windowX, windowY, windowWidth, windowHeight)
    }

    private fun closeTerminalWindow() {
        if (closingWindow) return
        closingWindow = true
        FloatingTerminalService.closeOverlay(this)
        finish()
    }

    private fun dragStart(rawX: Float, rawY: Float) {
        if (maximized) return
        gestureStartX = rawX; gestureStartY = rawY
        gestureWindowX = windowX; gestureWindowY = windowY
    }

    private fun dragWindow(rawX: Float, rawY: Float) {
        if (maximized) return
        val metrics = resources.displayMetrics
        windowX = (gestureWindowX + rawX - gestureStartX).toInt().coerceIn(0, (metrics.widthPixels - windowWidth).coerceAtLeast(0))
        windowY = (gestureWindowY + rawY - gestureStartY).toInt().coerceIn(0, (metrics.heightPixels - windowHeight).coerceAtLeast(0))
        applyWindowBounds()
    }

    private fun resizeStart(rawX: Float, rawY: Float) {
        if (maximized) return
        gestureStartX = rawX; gestureStartY = rawY
        gestureWindowWidth = windowWidth; gestureWindowHeight = windowHeight
    }

    private fun resizeWindow(rawX: Float, rawY: Float) {
        if (maximized) return
        val metrics = resources.displayMetrics
        windowWidth = (gestureWindowWidth + rawX - gestureStartX).toInt().coerceIn(dp(280), (metrics.widthPixels - windowX).coerceAtLeast(dp(280)))
        windowHeight = (gestureWindowHeight + rawY - gestureStartY).toInt().coerceIn(dp(240), (metrics.heightPixels - windowY).coerceAtLeast(dp(240)))
        applyWindowBounds()
    }

    private fun toggleMaximize() {
        val metrics = resources.displayMetrics
        if (!maximized) {
            restoreBounds = intArrayOf(windowX, windowY, windowWidth, windowHeight)
            windowX = 0; windowY = 0
            windowWidth = metrics.widthPixels; windowHeight = metrics.heightPixels
            maximized = true
        } else {
            windowX = restoreBounds[0]; windowY = restoreBounds[1]
            windowWidth = restoreBounds[2]; windowHeight = restoreBounds[3]
            maximized = false
        }
        applyWindowBounds()
        resizePty()
    }

    private fun showTerminalMenu(anchor: View) {
        // Use a popup attached to the overlay view; Activity dialogs are hidden behind the overlay.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(Color.rgb(35, 42, 57), 12)
        }
        val popup = PopupWindow(panel, dp(220), -2, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(rounded(Color.rgb(35, 42, 57), 12))
            elevation = dp(12).toFloat()
        }
        fun item(label: String, action: () -> Unit) {
            val row = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { popup.dismiss(); action() }
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        item("Choose distribution") { showCatalogue() }
        item("Clear terminal") { clearActiveTerminal() }
        item("Terminal help") { append("\nCommands: help, clear, exit. Install tools with apk, apt, dnf, pacman, etc.\n") }
        item("Close terminal") { closeTerminalWindow() }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
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
        // Re-check immediately before installing so an existing distro is never downloaded again.
        if (exists("alpine")) {
            active = "alpine"
            prefs.edit().putString("active_distro", "alpine").apply()
            append("\n[Alpine is already installed. Reusing the existing root filesystem; no download needed.]\n")
            runLinux("cat /etc/os-release; id")
            return
        }
        busy = true; terminalView.isEnabled = false
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
                val script = "set -e; TARGET='$alpineRoot'; STAGING='$alpineRoot.installing'; BACKUP='$alpineRoot.previous'; rm -rf \"\$STAGING\"; mkdir -p \"\$STAGING\"; tar -xzf '$a' -C \"\$STAGING\"; mkdir -p \"\$STAGING/etc/apk\" \"\$STAGING/dev\" \"\$STAGING/proc\" \"\$STAGING/sys\" \"\$STAGING/run\"; printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > \"\$STAGING/etc/resolv.conf\"; printf '%s\\n' 'https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/main' 'https://dl-cdn.alpinelinux.org/alpine/v$alpineVersion/community' > \"\$STAGING/etc/apk/repositories\"; test -x \"\$STAGING/bin/sh\" && test -d \"\$STAGING/usr\" && test -d \"\$STAGING/etc\"; printf '%s\\n' 'alpine:$alpineVersion' > \"\$STAGING/.mt-installed\"; rm -rf \"\$BACKUP\"; if [ -e \"\$TARGET\" ]; then mv \"\$TARGET\" \"\$BACKUP\"; fi; if mv \"\$STAGING\" \"\$TARGET\"; then rm -rf \"\$BACKUP\"; else [ ! -e \"\$BACKUP\" ] || mv \"\$BACKUP\" \"\$TARGET\"; exit 1; fi"
                val result = rootExec(script)
                archiveFile.delete()
                if (result.first != 0) error("Root extraction failed: ${result.second}")
                runOnUiThread {
                    active = "alpine"; prefs.edit().putString("active_distro", "alpine").apply()
                    append("[Alpine installed. Opening Alpine terminal…]\n")
                    append("Next: run apk update to refresh package indexes.\n")
                    dismissInstallProgress(); busy = false; terminalView.isEnabled = true; runLinux("cat /etc/os-release; id")
                }
            } catch (e: Exception) {
                archive?.delete()
                rootExec("rm -rf '$alpineRoot.installing'")
                runOnUiThread {
                    if (activityDestroyed || isFinishing || isDestroyed) return@runOnUiThread
                    dismissInstallProgress(); append("[Installation failed] ${e.message}\n")
                    append("No distro was selected. Retry from DISTROS.\n")
                    busy = false; terminalView.isEnabled = true
                }
            }
        }.start()
    }

    private fun fetchText(address: String): String {
        val c = (URL(address).openConnection() as HttpURLConnection).apply { connectTimeout = 45000; readTimeout = 90000; instanceFollowRedirects = true }
        try { if (c.responseCode !in 200..299) error("Checksum server HTTP ${c.responseCode}"); return c.inputStream.bufferedReader().use { it.readText() } }
        finally { c.disconnect() }
    }

    private fun download(address: String, file: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val c = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 45000; readTimeout = 120000; instanceFollowRedirects = true
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
        active = id
        prefs.edit().putString("active_distro", id).apply()
        startSession(id, "cat /etc/os-release; id")
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
        // Re-check immediately before downloading in case the catalogue state was stale.
        if (exists(d.id)) {
            active = d.id
            prefs.edit().putString("active_distro", d.id).apply()
            append("\n[${d.name} is already installed. Reusing the existing root filesystem; no download needed.]\n")
            runLinux("cat /etc/os-release; id")
            return
        }
        busy = true; terminalView.isEnabled = false
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
                val script = "set -e; TARGET='$target'; STAGING='$staging'; BACKUP=\"\${TARGET}.previous\"; mkdir -p \"\${TARGET%/*}\"; rm -rf \"\$STAGING\" \"\$BACKUP\"; mkdir -p \"\$STAGING\"; tar -xpf '$a' -C \"\$STAGING\"; test -x \"\$STAGING/bin/sh\"; mkdir -p \"\$STAGING/etc\" \"\$STAGING/dev\" \"\$STAGING/proc\" \"\$STAGING/sys\" \"\$STAGING/run\"; rm -f \"\$STAGING/etc/resolv.conf\"; printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > \"\$STAGING/etc/resolv.conf\"; if [ -e \"\$TARGET\" ]; then mv \"\$TARGET\" \"\$BACKUP\"; fi; if mv \"\$STAGING\" \"\$TARGET\"; then rm -rf \"\$BACKUP\"; else [ ! -e \"\$BACKUP\" ] || mv \"\$BACKUP\" \"\$TARGET\"; exit 1; fi; test -x \"\$TARGET/bin/sh\" && test -f \"\$TARGET/etc/os-release\" && test -d \"\$TARGET/usr/bin\" && test -d \"\$TARGET/var\"; printf '%s\\n' '${d.id}' > \"\$TARGET/.mt-installed\""
                val result = rootExec(script)
                if (result.first != 0) error("Root extraction failed: ${result.second}")
                runOnUiThread {
                    active = d.id; prefs.edit().putString("active_distro", d.id).apply()
                    append("[${d.name} installed. Opening terminal…]\n")
                    dismissInstallProgress(); busy = false; terminalView.isEnabled = true; runLinux("cat /etc/os-release; id")
                }
            } catch (e: Exception) {
                rootExec("rm -rf '$staging'")
                runOnUiThread { dismissInstallProgress(); append("[Installation failed] ${e.message}\n"); append("No distro was selected. Retry from DISTROS.\n"); busy = false; terminalView.isEnabled = true }
            } finally { archive?.delete(); tarFile?.delete() }
        }.start()
    }

    private fun unavailable(d: Distro) {
        AlertDialog.Builder(this).setTitle(d.name)
            .setMessage("The official ARM64 minimal rootfs downloader and its verification method are not integrated yet. This distribution is not downloadable in this build; MT-OS will not use an unverified archive.")
            .setPositiveButton("OK", null).show()
    }

    private fun renderControlButton() { ctrlButton?.apply { text = if (terminalBridge.isControlKeyEnabled()) "Ctrl ✓" else "Ctrl"; setTextColor(if (terminalBridge.isControlKeyEnabled()) Color.rgb(111,220,170) else Color.WHITE) } }

    private fun runCommand() { sendPty("\r") }

    internal fun focusTerminalInput() {
        if (::terminalView.isInitialized) {
            terminalView.isFocusable = true
            terminalView.isFocusableInTouchMode = true
            terminalView.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun runLinux(command: String) {
        val id = active ?: run { showCatalogue(); return }
        val current = activeSession
        if (current != null && current.distroId == id && current.vtSession != null && !current.starting) {
            if (command.isNotBlank()) sendPty(command + "\n")
        } else startSession(id, command)
    }

    private fun newSession() {
        val id = active
        if (id == null || !exists(id)) { showCatalogue(); return }
        startSession(id)
    }

    private fun startSession(distroId: String, initialCommand: String = "") {
        if (sessions.count { !it.closing } >= 4) {
            Toast.makeText(this, "Close an existing terminal tab before opening another (maximum 4).", Toast.LENGTH_LONG).show()
            return
        }
        if (!exists(distroId)) {
            append("\n[${name(distroId)} is not installed. Choose an installed distribution first.]\n")
            showCatalogue()
            return
        }
        val session = ShellSession(++sessionCounter, distroId)
        session.buffer.append("MT Terminal • ${name(distroId)}\n")
        sessions.add(session); activeSession = session
        renderSessionTabs(); output.text = session.buffer.toString()
        busy = true; terminalView.isEnabled = false
        val rootPath = root(distroId)
        appendToSession(session, "[Starting ${name(distroId)} interactive root shell…]\n")
        try {
            val shell = """R='$rootPath'; test -x "${'$'}R/bin/sh" || exit 20; mkdir -p "${'$'}R/dev" "${'$'}R/proc" "${'$'}R/sys" "${'$'}R/run"; mountpoint -q "${'$'}R/dev" || mount --bind /dev "${'$'}R/dev"; mkdir -p "${'$'}R/dev/pts"; mountpoint -q "${'$'}R/dev/pts" || mount -t devpts devpts "${'$'}R/dev/pts"; ln -sf /dev/pts/ptmx "${'$'}R/dev/ptmx"; mountpoint -q "${'$'}R/proc" || mount -t proc proc "${'$'}R/proc"; mountpoint -q "${'$'}R/sys" || mount -t sysfs sysfs "${'$'}R/sys"; chroot "${'$'}R" /bin/sh -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export HOME=/root USER=root LOGNAME=root TERM=xterm-256color COLORTERM=truecolor; export PS1="root@mt-gnome:~\${'$'} "; cd /root 2>/dev/null || cd /; exec /bin/sh -i'"""
            // Start from Android's guaranteed system shell, then let its PATH resolve
            // Magisk's su binary. Do not hard-code /system/bin/su: Samsung firmware may
            // expose su from /system/xbin or another PATH directory.
            // Resolve Magisk's su in the app's inherited Android environment first.
            // The terminal's restricted PATH previously hid the executable even though
            // rootExec() could launch it from the application process.
            val suPath = resolveSuExecutable()
            val quotedShell = "'" + shell.replace("'", "'\\''") + "'"
            val suCommand = "exec '$suPath' -c $quotedShell"
            val inheritedPath = System.getenv("PATH").orEmpty()
            val terminalPath = listOf(
                inheritedPath, "/system/bin", "/system/xbin", "/sbin",
                "/debug_ramdisk", "/vendor/bin", "/product/bin", "/usr/bin", "/bin"
            ).filter { it.isNotBlank() }.distinct().joinToString(":")
            val vt = TerminalSession(
                "/system/bin/sh",
                "/",
                arrayOf("/system/bin/sh", "-c", suCommand),
                arrayOf(
                    "HOME=/root",
                    "USER=root",
                    "LOGNAME=root",
                    "PATH=$terminalPath",
                    "TERM=xterm-256color",
                    "COLORTERM=truecolor"
                ),
                2000,
                terminalBridge
            )
            session.vtSession = vt
            terminalBridge.attachSession(vt)
            session.starting = true
            busy = true
            terminalView.isEnabled = false
            terminalView.requestFocus()
            renderSessionTabs()
            awaitSessionReady(session, initialCommand)
        } catch (e: Exception) {
            appendToSession(session, "[VT terminal startup failed] ${e.message}\n")
            closeSession(session)
            if (activeSession == null) activeSession = sessions.lastOrNull()
            if (activeSession != null) showActiveSession()
            else { busy = false; terminalView.isEnabled = false }
            renderSessionTabs()
        }
    }

    private fun awaitSessionReady(session: ShellSession, initialCommand: String, attempt: Int = 0) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed || session.closing || !sessions.contains(session)) return@postDelayed
            val vt = session.vtSession
            val alive = try { vt != null && vt.isRunning } catch (_: Exception) { false }
            if (alive && vt != null) {
                session.starting = false
                if (activeSession === session) {
                    busy = false
                    terminalView.isEnabled = true
                    terminalBridge.attachSession(vt)
                    terminalView.onScreenUpdated()
                    resizePty()
                    focusTerminalInput()
                }
                if (initialCommand.isNotBlank()) sendSession(session, initialCommand + "\n")
                renderSessionTabs()
            } else if (attempt < 80) {
                awaitSessionReady(session, initialCommand, attempt + 1)
            } else {
                appendToSession(session, "[Shell startup timed out: the PTY process did not become ready. The failed PTY is being closed.]\n")
                closeSession(session)
                if (activeSession == null) activeSession = sessions.lastOrNull()
                if (activeSession != null) showActiveSession()
                else {
                    busy = false
                    terminalView.isEnabled = false
                    output.text = "Shell startup failed • tap + to retry"
                }
                renderSessionTabs()
            }
        }, 100L)
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

    private fun renderSessionTabs() {
        if (!::sessionTabs.isInitialized) return
        sessionTabs.removeAllViews()
        sessions.forEach { session ->
            val selected = session === activeSession
            val tab = btn("${session.number} · ${name(session.distroId)}") { switchSession(session) }.apply {
                textSize = 9f
                setTextColor(if (selected) Color.rgb(111, 220, 170) else Color.LTGRAY)
                background = rounded(if (selected) Color.rgb(43, 58, 62) else Color.rgb(31, 36, 49), 8)
                setOnLongClickListener {
                    AlertDialog.Builder(this@TerminalActivity).setTitle("Close session ${session.number}?")
                        .setMessage("This ends processes running in this shell session.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Close") { _, _ ->
                            closeSession(session); sessions.remove(session)
                            if (activeSession === session) activeSession = sessions.lastOrNull()
                            showActiveSession(); renderSessionTabs()
                        }.show()
                    true
                }
            }
            sessionTabs.addView(tab, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(5) })
        }
        sessionTabs.addView(btn("+") { newSession() }.apply {
            textSize = 15f; setTextColor(Color.WHITE); background = rounded(Color.rgb(69, 83, 130), 8)
        }, LinearLayout.LayoutParams(dp(38), dp(34)))
    }

    private fun switchSession(session: ShellSession) {
        if (session.closing || !sessions.contains(session)) return
        val vt = session.vtSession
        val alive = try { vt != null && vt.isRunning } catch (_: Exception) { false }
        // A session still launching is not a dead session. Keep its tab attached
        // instead of destroying it and spawning another shell.
        if (!session.starting && (!alive || session.exited)) {
            appendToSession(session, "\n[This shell has exited. Use + to open a new session.]\n")
            return
        }
        activeSession = session
        active = session.distroId
        prefs.edit().putString("active_distro", active).apply()
        if (vt != null) terminalBridge.attachSession(vt) else terminalBridge.detachSession(null)
        showActiveSession()
        renderSessionTabs()
    }

    private fun showActiveSession() {
        val session = activeSession
        output.text = if (session == null) "No terminal session. Tap + to start one." else "MT Terminal • ${name(session.distroId)} • VT emulator"
        busy = session?.starting ?: false
        terminalView.isEnabled = !busy && (session?.vtSession?.let { try { it.isRunning } catch (_: Exception) { false } } == true)
        if (session?.vtSession != null) {
            terminalBridge.attachSession(session.vtSession!!)
            terminalView.onScreenUpdated()
        }
        scroll.post { resizePty() }
    }

    private fun appendToSession(session: ShellSession, value: String) {
        synchronized(session.buffer) { session.buffer.append(value) }
        if (activeSession === session && ::output.isInitialized) {
            output.text = value.trim().takeLast(240)
        }
    }

    private fun clearActiveTerminal() {
        val session = activeSession ?: return
        synchronized(session.buffer) { session.buffer.setLength(0) }
        session.vtSession?.reset()
        output.text = ""
    }

    private fun selectAllTerminalOutput() {
        terminalView.showContextMenu()
    }

    private fun copyTerminalSelection() {
        terminalView.showContextMenu()
        Toast.makeText(this, "Long-press terminal text to select and copy", Toast.LENGTH_SHORT).show()
    }

    private fun pasteClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) { Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show(); return }
        val value = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        activeSession?.vtSession?.let { session -> value.toByteArray(Charsets.UTF_8).let { session.write(it, 0, it.size) } }
    }

    private fun resizePty() {
        if (::terminalView.isInitialized) {
            terminalView.requestLayout()
            terminalView.invalidate()
        }
    }

    private fun sendPty(text: String) {
        val session = activeSession ?: return
        sendSession(session, text)
    }

    private fun sendSession(session: ShellSession, text: String) {
        val vt = session.vtSession
        val alive = try { !session.closing && vt != null && vt.isRunning } catch (_: Exception) { false }
        if (!alive || vt == null) {
            appendToSession(session, "\n[This terminal process has ended. Open the tab again to start a fresh shell.]\n")
            return
        }
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            vt.write(bytes, 0, bytes.size)
        } catch (e: Exception) {
            appendToSession(session, "[Terminal input error] ${e.message}\n")
        }
    }

    private fun closeSession(session: ShellSession) {
        val vt: TerminalSession?
        synchronized(sessionLock) {
            if (session.closing) return
            session.closing = true
            session.starting = false
            vt = session.vtSession
            session.vtSession = null
            sessions.remove(session)
            if (activeSession === session) activeSession = sessions.lastOrNull()
            if (vt != null) pendingShutdownByDistro[session.distroId] =
                (pendingShutdownByDistro[session.distroId] ?: 0) + 1
        }
        terminalBridge.detachSession(vt)
        if (vt == null) {
            session.exited = true
            return
        }
        Thread({
            try {
                if (vt.isRunning) vt.finishIfRunning()
            } catch (e: Exception) {
                android.util.Log.w("MT-GNOME", "Could not stop terminal session " + session.number, e)
            } finally {
                session.exited = true
                synchronized(sessionLock) {
                    val remaining = (pendingShutdownByDistro[session.distroId] ?: 1) - 1
                    if (remaining <= 0) pendingShutdownByDistro.remove(session.distroId)
                    else pendingShutdownByDistro[session.distroId] = remaining
                }
                cleanupDistroMountsIfIdle(session.distroId)
            }
        }, "MT-Terminal-close-${session.number}").apply { isDaemon = true }.start()
    }

    private fun cleanupDistroMountsIfIdle(distroId: String) {
        val safeToUnmount = synchronized(sessionLock) {
            (pendingShutdownByDistro[distroId] ?: 0) == 0 &&
                sessions.none { it.distroId == distroId && !it.closing && !it.exited }
        }
        if (!safeToUnmount) return
        try {
            val r = rootExec("R='${root(distroId)}'; mountpoint -q \"\$R/dev/pts\" && umount \"\$R/dev/pts\" || true; mountpoint -q \"\$R/dev\" && umount \"\$R/dev\" || true; mountpoint -q \"\$R/proc\" && umount \"\$R/proc\" || true; mountpoint -q \"\$R/sys\" && umount \"\$R/sys\" || true")
            if (r.first != 0) android.util.Log.w("MT-GNOME", "Distro mount cleanup returned ${r.first}: ${r.second}")
        } catch (e: Exception) {
            android.util.Log.w("MT-GNOME", "Distro mount cleanup failed", e)
        }
    }

    internal fun onVtSessionFinished(finished: TerminalSession) {
        val session = synchronized(sessionLock) { sessions.firstOrNull { it.vtSession === finished } } ?: return
        session.starting = false
        session.exited = true
        Thread({ cleanupDistroMountsIfIdle(session.distroId) }, "MT-Terminal-mount-cleanup").apply { isDaemon = true; start() }
        if (!activityDestroyed) runOnUiThread {
            if (activityDestroyed || isFinishing || isDestroyed) return@runOnUiThread
            if (activeSession === session) {
                busy = false
                terminalView.isEnabled = false
                output.text = "Shell exited • ${name(session.distroId)}"
            }
            renderSessionTabs()
        }
    }

    private fun closeAllSessions() {
        val closing = synchronized(sessionLock) {
            val snapshot = sessions.toList()
            sessions.clear()
            activeSession = null
            snapshot.forEach { it.closing = true; it.starting = false }
            snapshot.mapNotNull { session ->
                val vt = session.vtSession
                session.vtSession = null
                if (vt != null) pendingShutdownByDistro[session.distroId] =
                    (pendingShutdownByDistro[session.distroId] ?: 0) + 1
                if (vt == null) session.exited = true
                vt?.let { session to it }
            }
        }
        terminalBridge.detachSession(null)
        if (closing.isEmpty()) return
        Thread({
            closing.forEach { (session, vt) ->
                try {
                    if (vt.isRunning) vt.finishIfRunning()
                } catch (e: Exception) {
                    android.util.Log.w("MT-GNOME", "Could not stop terminal session " + session.number, e)
                } finally {
                    session.exited = true
                    synchronized(sessionLock) {
                        val remaining = (pendingShutdownByDistro[session.distroId] ?: 1) - 1
                        if (remaining <= 0) pendingShutdownByDistro.remove(session.distroId)
                        else pendingShutdownByDistro[session.distroId] = remaining
                    }
                }
            }
            closing.map { it.first.distroId }.distinct().forEach { cleanupDistroMountsIfIdle(it) }
        }, "MT-Terminal-close-all").apply { isDaemon = true }.start()
    }

    private fun cleanTerminalText(value: String): String {
        var text = value.replace(Regex("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)"), "")
        text = text.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
        text = text.replace(Regex("\u001B\\([B0]"), "")
        return text.replace("\r\n", "\n").replace('\r', '\n')
            .filter { it == '\n' || it == '\t' || it >= ' ' }
    }

    override fun onResume() {
        super.onResume()
        if (android.provider.Settings.canDrawOverlays(this) && overlayContent?.parent != null) {
            startService(Intent(this, FloatingTerminalService::class.java))
        }
    }

    override fun onDestroy() {
        activityDestroyed = true
        val ownsFloatingTerminal = hostActivity === this
        FloatingTerminalService.detachOverlay(this)
        if (ownsFloatingTerminal && hostActivity === this) {
            overlayContent = null
            hostActivity = null
            // An older Activity must never stop the service now owned by a
            // replacement Activity opened during a rapid close/reopen.
            stopService(Intent(this, FloatingTerminalService::class.java))
        }
        closeAllSessions()
        super.onDestroy()
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

    private fun append(s: String) {
        val session = activeSession
        if (session != null) appendToSession(session, s)
        else if (::output.isInitialized) { output.append(s); }
    }
    private fun btn(label: String, action: () -> Unit) = Button(this).apply { text = label; textSize = 10f; isAllCaps = false; setOnClickListener { action() } }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()


}