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
 * MT-OS Terminal. Commands are executed inside the Debian rootfs through Magisk.
 * The rootfs is shared with the MT-OS runtime; this activity never downloads or
 * replaces a rootfs image itself.
 */
class TerminalActivity : Activity() {
    private val rootfs = "/data/user/0/com.mt.os/files/rootfs"
    private val prefs by lazy { getSharedPreferences("mt_terminal", MODE_PRIVATE) }
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var busy = false

    private data class Desktop(val name: String, val subtitle: String, val pkg: String, val weight: String, val note: String)
    private val desktops = listOf(
        Desktop("XFCE", "Fast, traditional Linux desktop", "xfce4", "Moderate", "Best starting point for the A04e's limited RAM"),
        Desktop("LXQt", "Lightweight Qt desktop", "lxqt", "Moderate", "Low resource use; suitable for compact devices"),
        Desktop("MATE", "Classic, full-featured desktop", "mate-desktop-environment", "Large", "Traditional panel-and-window workflow"),
        Desktop("GNOME", "Modern GNOME desktop", "gnome-core", "Very large", "Heavy on this phone; installation may be slow"),
        Desktop("KDE Plasma", "Highly customizable desktop", "kde-plasma-desktop", "Very large", "Feature-rich but demanding on phone storage and RAM"),
        Desktop("Cinnamon", "Familiar desktop layout", "cinnamon-desktop-environment", "Very large", "A full desktop stack with higher resource needs"),
        Desktop("Phosh", "Mobile-first Linux shell", "phosh", "Moderate", "Designed for touchscreens; complements MT-Phoc/Phosh work")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildTerminal()
        if (prefs.getString("selected_desktop", null) == null) showDesktopCatalogue()
        else append("MT-OS Terminal ready. Type help for commands.\n")
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
        header.addView(actionButton("DESKTOPS") { showDesktopCatalogue() })
        header.addView(actionButton("CLEAR") { output.text = "" })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "LINUX SHELL  •  ROOTFS SESSION"
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
            text = "MT-OS Terminal [version 0.1]\n"
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

    private fun showDesktopCatalogue() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        panel.addView(TextView(this).apply {
            text = "Choose a Linux desktop"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(this).apply {
            text = "Select one environment to install into the existing Debian root filesystem. Downloads require an internet connection and free storage. You can change your choice later."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(10))
        })
        val dialogScroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        desktops.forEach { desktop ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = round(Color.rgb(34, 42, 58), 12)
                setOnClickListener { confirmInstall(desktop) }
            }
            card.addView(TextView(this).apply {
                text = desktop.name + "   ·   " + desktop.weight
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = desktop.subtitle + "\n" + desktop.note
                textSize = 11f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(4), 0, 0)
            })
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        dialogScroll.addView(list)
        panel.addView(dialogScroll, LinearLayout.LayoutParams(-1, dp(330)))
        AlertDialog.Builder(this)
            .setView(panel)
            .setNegativeButton("Not now", null)
            .setNeutralButton("Installed desktop", null)
            .show()
    }

    private fun confirmInstall(desktop: Desktop) {
        AlertDialog.Builder(this)
            .setTitle("Install ${desktop.name}?")
            .setMessage("MT-OS will run apt inside the existing Debian rootfs and install ${desktop.pkg}. This can download many packages and use substantial storage. Keep the phone connected to power and Wi-Fi. The desktop packages will be installed; launching a graphical Linux session still depends on the configured display server.")
            .setPositiveButton("Download and install") { _, _ -> installDesktop(desktop) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installDesktop(desktop: Desktop) {
        if (busy) return
        prefs.edit().putString("selected_desktop", desktop.name).apply()
        append("\n\$ Installing ${desktop.name}…\n")
        runRootfs("export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y --no-install-recommends ${desktop.pkg}")
    }

    private fun runCommand() {
        if (busy) return
        val command = input.text.toString().trim()
        if (command.isEmpty()) return
        input.text.clear()
        append("\n\$ $command\n")
        if (command == "help") {
            append("Commands run inside the Debian rootfs. Try: pwd, ls, uname -a, apt update, apt install <package>, df -h, free -h, whoami, exit.\n")
            return
        }
        if (command == "clear") { output.text = ""; return }
        if (command == "exit") { finish(); return }
        runRootfs(command)
    }

    private fun runRootfs(command: String) {
        busy = true
        input.isEnabled = false
        append("[starting Linux shell…]\n")
        Thread {
            try {
                val quoted = "'" + command.replace("'", "'\\''") + "'"
                val script = "test -x '$rootfs/bin/sh' || { echo 'ERROR: Debian rootfs not found at $rootfs'; exit 20; }; chroot '$rootfs' /bin/bash -lc $quoted 2>&1"
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
