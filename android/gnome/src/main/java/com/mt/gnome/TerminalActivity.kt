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
import java.io.InputStreamReader

/**
 * MT-OS Terminal and minimal Linux distribution catalogue.
 * No desktop environment or graphical Linux package is installed here.
 */
class TerminalActivity : Activity() {
    private val rootfs = "/data/user/0/com.mt.os/files/rootfs"
    private val prefs by lazy { getSharedPreferences("mt_terminal", MODE_PRIVATE) }
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private var busy = false

    private data class Distro(
        val name: String,
        val summary: String,
        val manager: String,
        val state: String,
        val detail: String
    )

    // Entries are deliberately base-system/CLI-only. Availability is not implied
    // until an official ARM64 rootfs and its checksum can be verified.
    private val distributions = listOf(
        Distro("Alpine Linux", "Tiny, security-oriented base system", "apk", "Catalogue", "BusyBox and musl; excellent low-footprint option."),
        Distro("Debian", "Stable minimal ARM64 base", "apt", "Catalogue", "Install only essential packages; no desktop task."),
        Distro("Ubuntu", "Minimal Ubuntu command-line system", "apt", "Available locally", "Uses the existing MT-OS Ubuntu rootfs. No desktop packages are added."),
        Distro("Arch Linux ARM", "Lean rolling-release base", "pacman", "Catalogue", "Minimal base; package availability depends on ARM64 image source."),
        Distro("Fedora", "Minimal RPM-based system", "dnf", "Catalogue", "No workstation or desktop groups."),
        Distro("Void Linux", "Independent minimal distribution", "xbps", "Catalogue", "runit-based system with a small base."),
        Distro("Kali Linux", "Minimal security-focused base", "apt", "Catalogue", "Base system only; no large tool collections."),
        Distro("Manjaro ARM", "ARM-focused minimal base", "pacman", "Catalogue", "Availability depends on a maintained minimal ARM64 image."),
        Distro("Gentoo", "Customisable minimal base", "emerge", "Catalogue", "Advanced setup; compiling packages can be demanding on this phone."),
        Distro("openSUSE", "Minimal RPM-based base", "zypper", "Catalogue", "No graphical patterns or desktop packages."),
        Distro("Rocky Linux", "Minimal enterprise-style base", "dnf", "Catalogue", "ARM64 rootfs availability must be verified."),
        Distro("Buildroot", "Build-your-own tiny Linux rootfs", "custom", "Catalogue", "Embedded root filesystem builder, not a general-purpose distro installer.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildTerminal()
        if (prefs.getBoolean("ubuntu_selected", false)) {
            append("MT-OS Terminal ready. Active environment: existing Ubuntu rootfs. Type help for commands.\n")
        } else {
            showDistroCatalogue()
        }
    }

    private fun buildTerminal() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(13, 17, 25))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "MT-OS  /  TERMINAL"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(actionButton("DISTROS") { showDistroCatalogue() })
        header.addView(actionButton("CLEAR") { output.text = "" })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "MINIMAL LINUX  •  COMMAND-LINE ONLY"
            textSize = 10f
            setTextColor(Color.rgb(111, 194, 168))
            setPadding(0, 0, 0, dp(10))
        })
        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackground(round(Color.rgb(7, 11, 17), 12))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        output = TextView(this).apply {
            text = "MT-OS Terminal [version 0.2]\n"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(218, 229, 239))
            setTextIsSelectable(true)
        }
        scroll.addView(output)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        val commandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        input = EditText(this).apply {
            hint = "Enter a Linux command…"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 13f
            singleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12), 0, dp(12), 0)
            background = round(Color.rgb(30, 38, 51), 10)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { runCommand(); true } else false
            }
        }
        commandRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        commandRow.addView(actionButton("RUN") { runCommand() }.apply {
            setTextColor(Color.WHITE)
            background = round(Color.rgb(92, 75, 180), 10)
        }, LinearLayout.LayoutParams(dp(72), dp(46)).apply { leftMargin = dp(8) })
        root.addView(commandRow)
        setContentView(root)
    }

    private fun showDistroCatalogue() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        panel.addView(TextView(this).apply {
            text = "Minimal Linux distributions"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(this).apply {
            text = "Choose a command-line Linux system. Every entry is desktop-free: no GNOME, KDE, XFCE, LXQt, MATE, Phosh or graphical application bundles. Only the existing MT-OS Ubuntu environment is currently connected. Other ARM64 installers will be enabled only after their official rootfs and checksums are verified."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(10))
        })
        val dialogScroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        distributions.forEach { distro ->
            val available = distro.state == "Available locally"
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = round(Color.rgb(34, 42, 58), 12)
                setOnClickListener {
                    if (available) selectExistingUbuntu()
                    else showUnavailable(distro)
                }
            }
            card.addView(TextView(this).apply {
                text = distro.name + "   ·   " + distro.state
                textSize = 15f
                setTextColor(if (available) Color.rgb(111, 220, 170) else Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = distro.summary + "\nPackage manager: " + distro.manager + "\n" + distro.detail
                textSize = 11f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(4), 0, 0)
            })
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        dialogScroll.addView(list)
        panel.addView(dialogScroll, LinearLayout.LayoutParams(-1, dp(360)))
        AlertDialog.Builder(this)
            .setView(panel)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun selectExistingUbuntu() {
        AlertDialog.Builder(this)
            .setTitle("Use existing MT-OS Ubuntu?")
            .setMessage("This opens the Ubuntu root filesystem already used by MT-OS. No desktop environment or graphical package will be installed, and the existing Phosh/Phoc setup will not be changed.")
            .setPositiveButton("Open terminal") { _, _ ->
                prefs.edit().putBoolean("ubuntu_selected", true).apply()
                append("\n[Selected existing MT-OS Ubuntu command-line environment]\n")
                verifyRootfs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUnavailable(distro: Distro) {
        AlertDialog.Builder(this)
            .setTitle(distro.name)
            .setMessage("This distribution is listed as a minimal, command-line-only option, but its ARM64 root filesystem and matching checksum have not yet been integrated. It is not downloadable in this build. MT-OS will not offer an unverified or fabricated download.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun verifyRootfs() {
        runRootfs("printf 'Ubuntu rootfs ready: '; . /etc/os-release && printf '%s\\n' \"$PRETTY_NAME\"; id")
    }

    private fun runCommand() {
        if (busy) return
        val command = input.text.toString().trim()
        if (command.isEmpty()) return
        input.text.clear()
        append("\n\$ $command\n")
        if (command == "help") {
            append("Commands run inside the existing MT-OS Ubuntu rootfs. Try: pwd, ls, uname -a, apt update, apt install <package>, df -h, free -h, whoami, exit. No desktop installer is provided.\n")
            return
        }
        if (command == "clear") { output.text = ""; return }
        if (command == "exit") { finish(); return }
        runRootfs(command)
    }

    private fun runRootfs(command: String) {
        if (busy) return
        busy = true
        input.isEnabled = false
        append("[starting Linux shell…]\n")
        Thread {
            try {
                val quoted = "'" + command.replace("'", "'\\''") + "'"
                val script = "test -x '$rootfs/bin/sh' || { echo 'ERROR: existing MT-OS Ubuntu rootfs not found at $rootfs'; exit 20; }; chroot '$rootfs' /bin/bash -lc $quoted 2>&1"
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val item = line ?: continue
                        runOnUiThread { append(item + "\n") }
                    }
                }
                val code = process.waitFor()
                runOnUiThread { append("[process exited: $code]\n") }
            } catch (e: Exception) {
                runOnUiThread { append("[terminal error] ${e.message}\n") }
            } finally {
                runOnUiThread {
                    busy = false
                    input.isEnabled = true
                    input.requestFocus()
                }
            }
        }.start()
    }

    private fun append(value: String) {
        output.append(value)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 10f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun round(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
