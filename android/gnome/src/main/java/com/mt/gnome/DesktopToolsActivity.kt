package com.mt.gnome

import android.app.Activity
import android.app.ActivityManager
import android.content.IntentFilter
import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.Locale

/** Native desktop utility hub: live device monitor and Android Storage Access Framework entry points. */
class DesktopToolsActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var monitorText: TextView
    private val blue = Color.rgb(120, 165, 255)
    private val muted = Color.rgb(174, 180, 199)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 22, 31)
        window.navigationBarColor = Color.rgb(18, 22, 31)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(17, 20, 29))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("MT-GNOME Desktop Tools", 19, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("Close") { finish() })
        root.addView(header)
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        // Keep the tools hub inside the MT-GNOME desktop as a resizable-style
        // application window rather than taking over the entire Android screen.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.CENTER)
        window.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            (resources.displayMetrics.heightPixels * 0.84f).toInt()
        )
        buildTools()
    }

    private fun buildTools() {
        body.removeAllViews()
        section("System monitor", "Live information from Android and Linux kernel procfs.")
        monitorText = text("", 12, Color.rgb(220, 226, 240))
        card(monitorText)
        row(button("Refresh") { refreshMonitor() }, button("Android settings") {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        })
        refreshMonitor()

        section("Files", "Use Android's Storage Access Framework to browse shared storage and choose files. Android controls access to protected folders.")
        row(button("Browse folders") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            try { startActivity(intent) } catch (_: Exception) { toast("No document provider is available") }
        }, button("Open a file") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            try { startActivity(intent) } catch (_: Exception) { toast("No file picker is available") }
        })

        section("Desktop controls", "Shortcuts to Android-managed controls. Android remains responsible for privileged radios and system policies.")
        row(button("Notifications") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            button("Display") { openSettings(Settings.ACTION_DISPLAY_SETTINGS) })
        row(button("Wi-Fi") { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            button("Bluetooth") { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) })
        row(button("Sound") { openSettings(Settings.ACTION_SOUND_SETTINGS) },
            button("Battery") { openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS) })
        row(button("Language") { openSettings(Settings.ACTION_LOCALE_SETTINGS) },
            button("Accessibility") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        row(button("App permissions") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        }, button("Overlay permission") {
            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        })
        row(button("Developer options") { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
            button("Default apps") { openSettings(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) })
        row(button("VPN") { openSettings(Settings.ACTION_VPN_SETTINGS) },
            button("Date & time") { openSettings(Settings.ACTION_DATE_SETTINGS) })

        section("Clipboard", "Quick access to the current clipboard item. Android may restrict background clipboard reads.")
        row(button("View clipboard") { showClipboard() },
            button("Clear clipboard") { clearClipboard() })

        section("Device status", "Battery, charging and hardware status reported by Android.")
        val batteryText = text(readBatteryStatus(), 12, Color.rgb(220, 226, 240))
        card(batteryText)
        row(button("Refresh all status") { refreshMonitor(); batteryText.text = readBatteryStatus() },
            button("App information") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")))
            })
        section("Workspaces", "Android does not expose independent virtual desktops to ordinary apps. MT-GNOME can provide saved desktop profiles, but true independent workspaces require an in-app window manager.")
        val prefs = getSharedPreferences("mt_gnome", MODE_PRIVATE)
        val active = prefs.getInt("active_workspace", 1)
        row(*(1..4).map { number ->
            button(if (number == active) "Workspace $number ✓" else "Workspace $number") {
                prefs.edit().putInt("active_workspace", number).apply()
                toast("Workspace profile $number selected. Independent window spaces are not yet implemented.")
                buildTools()
            }
        }.toTypedArray())
    }

    private fun readBatteryStatus(): String {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Battery information unavailable"
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = if (level >= 0) level * 100 / scale else -1
        val status = when (battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }
        val plugged = when (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Not connected"
        }
        val temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val tempText = if (temperature >= 0) String.format(Locale.US, "%.1f °C", temperature / 10f) else "Unavailable"
        return "Battery: " + (if (percent >= 0) "$percent%" else "Unavailable") +
            "\nStatus: " + status + "\nPower source: " + plugged + "\nBattery temperature: " + tempText
    }

    private fun showClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = try {
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null }
        android.app.AlertDialog.Builder(this)
            .setTitle("Clipboard")
            .setMessage(value?.takeIf { it.isNotBlank() } ?: "Clipboard is empty or Android denied access.")
            .setPositiveButton("Copy text") { _, _ ->
                if (!value.isNullOrBlank()) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MT-GNOME", value))
                    toast("Clipboard text copied")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun clearClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            toast("Clipboard cleared")
        } catch (_: Exception) { toast("Android did not allow clipboard access") }
    }

    private fun refreshMonitor() {
        if (!::monitorText.isInitialized) return
        val mem = readMemInfo()
        val data = StatFs(Environment.getDataDirectory().absolutePath)
        val total = data.totalBytes
        val free = data.availableBytes
        val used = (total - free).coerceAtLeast(0)
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        val lines = listOf(
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            "Processors: ${runtime.availableProcessors()}",
            "RAM total: ${formatBytes(info.totalMem)}",
            "RAM available: ${formatBytes(info.availMem)}",
            "Kernel MemTotal: ${mem["MemTotal"] ?: "unavailable"}",
            "Kernel MemAvailable: ${mem["MemAvailable"] ?: "unavailable"}",
            "Storage used: ${formatBytes(used)} / ${formatBytes(total)}",
            "Storage free: ${formatBytes(free)}",
            "App heap: ${formatBytes(runtime.totalMemory() - runtime.freeMemory())} / ${formatBytes(runtime.maxMemory())}",
            "Uptime: ${android.os.SystemClock.uptimeMillis() / 1000}s"
        )
        monitorText.text = lines.joinToString("\n")
    }

    private fun readMemInfo(): Map<String, String> = try {
        File("/proc/meminfo").readLines().take(12).associate {
            val parts = it.split(Regex("\\s+"), limit = 3)
            parts[0].removeSuffix(":") to if (parts.size > 1) parts.drop(1).joinToString(" ") else "unknown"
        }
    } catch (_: Exception) { emptyMap() }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var index = -1
        do { amount /= 1024.0; index++ } while (amount >= 1024 && index < units.lastIndex)
        return String.format(Locale.US, "%.1f %s", amount, units[index])
    }

    private fun section(title: String, description: String) {
        body.addView(text(title, 16, Color.WHITE, true).apply { setPadding(0, dp(18), 0, dp(3)) })
        body.addView(text(description, 10, muted).apply { setPadding(0, 0, 0, dp(8)) })
    }

    private fun card(view: View) {
        val wrap = LinearLayout(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(34, 39, 53)); cornerRadius = dp(12).toFloat()
            }
        }
        wrap.addView(view)
        body.addView(wrap, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun row(vararg views: View) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { line.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }) }
        body.addView(line)
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 11f; setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 58, 82))
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) } catch (_: Exception) { toast("This settings page is unavailable on this Android version") }
    }

    private fun toast(value: String) = android.widget.Toast.makeText(this, value, android.widget.Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
