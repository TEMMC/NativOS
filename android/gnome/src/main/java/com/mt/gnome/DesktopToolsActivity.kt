package com.mt.gnome

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.app.KeyguardManager
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
        row(button("Refresh") { refreshMonitor() }, button("Export diagnostics") { exportDiagnostics() })
        row(button("Android settings") {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        })
        refreshMonitor()

        section("Security & lock screen", "Choose the screen-lock style in Android's trusted setup. MT-GNOME never stores your PIN, pattern or password.")
        row(button("Choose PIN") { configureScreenLock("PIN") },
            button("Choose pattern") { configureScreenLock("pattern") },
            button("Choose password") { configureScreenLock("password") })
        row(button("Lock device now") { lockDevice() },
            button("Screen lock settings") { openSettings(Settings.ACTION_SECURITY_SETTINGS) })

        section("Photos, video & camera", "Open Android's photo and video pickers or launch the camera. Media stays under Android's permission and picker controls.")
        row(button("Photos") { openMediaPicker("image/*", "Select a photo") },
            button("Videos") { openMediaPicker("video/*", "Select a video") })
        row(button("Camera") { openExternal(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)) },
            button("Record video") { openExternal(Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE)) })

        section("Notification centre", "Manage app alerts, notification-listener access and Do Not Disturb through Android's system controls.")
        row(button("App notifications") {
            try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
            catch (_: Exception) { openSettings(Settings.ACTION_SETTINGS) }
        }, button("Notification access") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
        row(button("Do Not Disturb") { openSettings(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS) },
            button("Lock-screen alerts") { openSettings(Settings.ACTION_SECURITY_SETTINGS) })

        section("Files", "Browse folders you explicitly grant to MT-GNOME. Create folders, open, rename, delete and share documents through Android's Storage Access Framework.")
        row(button("Open MT Files") {
            try { startActivity(Intent(this, FileManagerActivity::class.java)) }
            catch (_: Exception) { toast("Could not open MT Files") }
        }, button("Choose a folder") {
            try { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )) } catch (_: Exception) { toast("No document provider is available") }
        })
        row(button("Open a file") {
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

        section("Productivity", "Lightweight native shortcuts and offline notes.")
        row(button("Notes") { openNotes() }, button("Calendar") { openExternal(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)) })
        row(button("Clock & alarms") { openExternal(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) },
            button("Web browser") { openExternal(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))) })
        row(button("Downloads") {
            openExternal(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("content://downloads/my_downloads")))
        }, button("Share text") { shareNotes() })

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
        section("Applications & processes", "Browse launchable Android apps and inspect processes visible to this app. Android may hide other apps' process details.")
        row(button("Installed apps") { showInstalledApps() },
            button("Running processes") { showRunningProcesses() })
        row(button("Open app settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }, button("Manage storage") {
            openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        })

        section("Desktop profiles", "Save four independent preference profiles. Android app windows remain managed by Android; this does not yet create isolated window workspaces.")
        val prefs = getSharedPreferences("mt_gnome", MODE_PRIVATE)
        val active = prefs.getInt("active_workspace", 1)
        row(*(1..4).map { number ->
            button(if (number == active) "Profile $number ✓" else "Profile $number") {
                prefs.edit().putInt("active_workspace", number).apply()
                toast("Desktop profile $number selected")
                buildTools()
            }
        }.toTypedArray())
        row(button("Rename active profile") { renameProfile(active) },
            button("Reset profile name") {
                prefs.edit().remove("workspace_name_$active").apply()
                buildTools()
            })
    }

    private fun openMediaPicker(mime: String, title: String) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = mime
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(intent, title)) }
        catch (_: Exception) { toast("No compatible media picker is available") }
    }

    private fun configureScreenLock(method: String) {
        getSharedPreferences("mt_gnome_security", MODE_PRIVATE).edit()
            .putString("preferred_lock_method", method).apply()
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val message = if (keyguard.isDeviceSecure) {
            "Android already has a secure screen lock. To change it to $method, open Android Security settings and follow its screen-lock change flow. Android may ask for your current credential first."
        } else {
            "Next, Android will open its trusted screen-lock setup. Choose $method there and complete the setup. MT-GNOME does not see or store your credential."
        }
        AlertDialog.Builder(this).setTitle("Set up $method")
            .setMessage(message)
            .setPositiveButton("Open Android security") { _, _ ->
                openSettings(Settings.ACTION_SECURITY_SETTINGS)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun lockDevice() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            AlertDialog.Builder(this).setTitle("Set a secure screen lock first")
                .setMessage("Choose a PIN, pattern or password in Android Security settings before using device lock. This protects your device with Android's real lock screen.")
                .setPositiveButton("Set up") { _, _ -> openSettings(Settings.ACTION_SECURITY_SETTINGS) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val manager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MTGNOMEDeviceAdminReceiver::class.java)
        if (manager.isAdminActive(admin)) {
            try { manager.lockNow() } catch (_: SecurityException) { toast("Android denied the lock request") }
            return
        }
        val request = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Allows the Lock device button in MT-GNOME to invoke Android's normal secure lock screen. It does not read your credential.")
        }
        try { startActivityForResult(request, REQUEST_DEVICE_ADMIN) }
        catch (_: Exception) { toast("Could not open Android device-admin activation") }
    }

    @Deprecated("Android activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEVICE_ADMIN) {
            val manager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, MTGNOMEDeviceAdminReceiver::class.java)
            if (manager.isAdminActive(admin)) {
                try { manager.lockNow() } catch (_: SecurityException) { toast("Android denied the lock request") }
            } else toast("Device lock permission was not enabled")
        }
    }

    private fun openExternal(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast("No compatible application is installed") }
    }

    private fun openNotes() {
        val prefs = getSharedPreferences("mt_gnome_notes", MODE_PRIVATE)
        val editor = android.widget.EditText(this).apply {
            minLines = 8
            gravity = Gravity.TOP or Gravity.START
            setText(prefs.getString("notes", ""))
            hint = "Write notes. They are saved locally on this device."
            textSize = 14f
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("MT-GNOME Notes")
            .setView(editor)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("notes", editor.text.toString()).apply()
                toast("Notes saved on this device")
            }
            .setNeutralButton("Share") { _, _ ->
                prefs.edit().putString("notes", editor.text.toString()).apply()
                shareText(editor.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareNotes() {
        val value = getSharedPreferences("mt_gnome_notes", MODE_PRIVATE).getString("notes", "").orEmpty()
        if (value.isBlank()) { openNotes(); return }
        shareText(value)
    }

    private fun shareText(value: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }
        try { startActivity(Intent.createChooser(intent, "Share from MT-GNOME")) }
        catch (_: Exception) { toast("No sharing application is available") }
    }

    private fun renameProfile(number: Int) {
        val editor = android.widget.EditText(this).apply {
            setSingleLine(true)
            hint = "Profile name"
            setText(getSharedPreferences("mt_gnome", MODE_PRIVATE).getString("workspace_name_$number", "Profile $number"))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Rename desktop profile")
            .setView(editor)
            .setPositiveButton("Save") { _, _ ->
                val name = editor.text.toString().trim().take(32)
                if (name.isNotEmpty()) getSharedPreferences("mt_gnome", MODE_PRIVATE)
                    .edit().putString("workspace_name_$number", name).apply()
                buildTools()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInstalledApps() {
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launchIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
        if (apps.isEmpty()) {
            toast("No launchable applications found")
            return
        }
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Installed applications")
            .setItems(labels) { _, index ->
                val info = apps[index]
                val launch = packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
                if (launch != null) {
                    try { startActivity(launch) } catch (_: Exception) { toast("Could not launch " + labels[index]) }
                } else toast("No launcher activity for " + labels[index])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRunningProcesses() {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val processes = try { manager.runningAppProcesses.orEmpty() } catch (_: Exception) { emptyList() }
        val message = if (processes.isEmpty()) {
            "Android did not expose process details to MT-GNOME. This is normal on modern Android versions."
        } else {
            processes.sortedBy { it.processName }.joinToString("\\n") {
                it.processName + "  ·  PID " + it.pid + "  ·  " + it.importanceReasonCode
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Visible running processes")
            .setMessage(message)
            .setPositiveButton("Refresh") { _, _ -> showRunningProcesses() }
            .setNegativeButton("Close", null)
            .show()
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
            "Storage volumes: ${getExternalFilesDirs(null).count { it != null }} available",
            "App heap: ${formatBytes(runtime.totalMemory() - runtime.freeMemory())} / ${formatBytes(runtime.maxMemory())}",
            "Uptime: ${android.os.SystemClock.uptimeMillis() / 1000}s"
        )
        monitorText.text = lines.joinToString("\n")
    }

    private fun exportDiagnostics() {
        val mem = readMemInfo()
        val data = StatFs(Environment.getDataDirectory().absolutePath)
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memory)
        val report = buildString {
            appendLine("MT-GNOME diagnostics")
            appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())}")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("App version: ${packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"}")
            appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Processors: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("RAM total: ${formatBytes(memory.totalMem)}")
            appendLine("RAM available: ${formatBytes(memory.availMem)}")
            appendLine("Kernel MemAvailable: ${mem["MemAvailable"] ?: "unavailable"}")
            appendLine("Data storage total: ${formatBytes(data.totalBytes)}")
            appendLine("Data storage available: ${formatBytes(data.availableBytes)}")
            appendLine("External app volumes: ${getExternalFilesDirs(null).count { it != null }}")
            appendLine("App PID: ${android.os.Process.myPid()}")
            appendLine("Uptime: ${android.os.SystemClock.uptimeMillis() / 1000}s")
            appendLine()
            appendLine("This report excludes account credentials, file contents, and precise location.")
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "MT-GNOME diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        try { startActivity(Intent.createChooser(share, "Share diagnostics")) }
        catch (_: Exception) { toast("No sharing application is available") }
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
    companion object { private const val REQUEST_DEVICE_ADMIN = 4208 }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
