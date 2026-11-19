package com.mt.gnome

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GnomeHomeActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("mt_gnome", MODE_PRIVATE) }
    private val muted = Color.rgb(174, 180, 199)
    private lateinit var desktop: LinearLayout
    private lateinit var appGrid: GridLayout
    private lateinit var search: EditText
    private lateinit var clock: TextView
    private lateinit var resolution: TextView
    private lateinit var rootStatus: TextView
    private var apps: List<ResolveInfo> = emptyList()
    private var wallpaperName = "Aurora"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wallpaperName = prefs.getString("wallpaper", prefs.getString("theme", "Aurora")) ?: "Aurora"
        applyOrientationPreference()
        window.statusBarColor = Color.rgb(30, 30, 36)
        window.navigationBarColor = Color.rgb(30, 30, 36)
        enterDesktopMode()
        buildDesktop()
        loadApps()
        updateRootStatus()
        showFirstRunSetupIfNeeded()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        buildDesktop()
        renderApps()
        updateResolution()
        updateRootStatus()
    }

    override fun onResume() {
        super.onResume()
        enterDesktopMode()
        if (::clock.isInitialized) updateClock()
        if (::resolution.isInitialized) updateResolution()
        if (::rootStatus.isInitialized) updateRootStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterDesktopMode()
    }

    @Suppress("DEPRECATION")
    private fun enterDesktopMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    // Five built-in desktop wallpapers. Each is rendered locally; no network download is needed.
    private fun palette(): IntArray = when (wallpaperName) {
        "Emerald" -> intArrayOf(Color.rgb(8, 112, 91), Color.rgb(9, 62, 74), Color.rgb(8, 28, 43))
        "Midnight" -> intArrayOf(Color.rgb(61, 65, 120), Color.rgb(32, 39, 78), Color.rgb(10, 16, 38))
        "Sunset" -> intArrayOf(Color.rgb(190, 77, 86), Color.rgb(112, 48, 104), Color.rgb(37, 28, 68))
        "Arctic" -> intArrayOf(Color.rgb(35, 139, 178), Color.rgb(32, 91, 143), Color.rgb(20, 42, 86))
        else -> intArrayOf(Color.rgb(105, 70, 176), Color.rgb(54, 66, 135), Color.rgb(17, 25, 61))
    }

    private fun buildDesktop() {
        desktop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette())
        }

        // GNOME-style top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(30, 30, 36), 12)
            setPadding(dp(13), 0, dp(12), 0)
        }
        top.addView(label("Activities", 12, Color.WHITE, true).apply {
            setPadding(0, 0, dp(18), 0)
            setOnClickListener { focusSearch() }
        })
        top.addView(label("MT", 12, Color.rgb(194, 176, 255), true))
        top.addView(label("  Desktop", 12, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        clock = label(now(), 12, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        top.addView(clock)
        top.addView(label("  ▾", 17, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { showQuickSettings() }
        })
        desktop.addView(top, LinearLayout.LayoutParams(-1, dp(38)))

        val workArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
        }

        // GNOME dash / dock
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            background = rounded(Color.argb(220, 29, 30, 42), 18)
            setPadding(dp(7), dp(10), dp(7), dp(10))
        }
        addDockButton(dock, "▦", "Apps") { focusSearch() }
        addDockButton(dock, "⌘", "Terminal") { openTerminalWindow() }
        addDockButton(dock, "⚙", "Settings") { showAppearance() }
        addDockButton(dock, "⌂", "System") { showQuickSettings() }
        workArea.addView(dock, LinearLayout.LayoutParams(dp(66), -1))

        val workspace = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(3), 0)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(label("Applications", 19, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        resolution = label("", 10, muted, true).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(resolution)
        workspace.addView(heading, LinearLayout.LayoutParams(-1, dp(32)))
        workspace.addView(label("Your desktop workspace", 10, muted).apply {
            setPadding(0, dp(1), 0, dp(8))
        })

        search = EditText(this).apply {
            hint = "  Search apps…"
            textSize = 13f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setPadding(dp(13), 0, dp(13), 0)
            background = rounded(Color.argb(220, 37, 40, 61), 13)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderApps() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        workspace.addView(search, LinearLayout.LayoutParams(-1, dp(42)))

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            setPadding(0, dp(8), 0, dp(4))
        }
        appGrid = GridLayout(this).apply {
            columnCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 7 else 4
            useDefaultMargins = false
        }
        scroll.addView(appGrid)
        workspace.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), 0)
        }
        rootStatus = label("Checking root access…", 9, muted, true)
        footer.addView(rootStatus, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(label("MT-OS  •  GNOME-style session", 9, muted))
        workspace.addView(footer)
        workArea.addView(workspace, LinearLayout.LayoutParams(0, -1, 1f))
        desktop.addView(workArea, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(desktop)
        updateClock()
        updateResolution()
    }

    private fun loadApps() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
        renderApps()
    }

    private fun renderApps() {
        if (!::appGrid.isInitialized) return
        appGrid.removeAllViews()
        val term = if (::search.isInitialized) search.text.toString().trim() else ""
        val results = apps.filter { it.loadLabel(packageManager).toString().contains(term, true) }
        results.forEach { info ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(9), dp(5), dp(7))
                background = rounded(Color.argb(55, 205, 205, 235), 14)
                setOnClickListener { launchApp(info) }
            }
            cell.addView(ImageView(this).apply {
                setImageDrawable(info.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            cell.addView(label(info.loadLabel(packageManager).toString(), 10, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(31)))
            appGrid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(94)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        if (results.isEmpty()) {
            appGrid.addView(label("No applications found", 13, Color.WHITE, true).apply {
                setPadding(dp(12), dp(22), 0, 0)
            }, GridLayout.LayoutParams().apply { columnSpec = GridLayout.spec(0, appGrid.columnCount) })
        }
    }

    private fun launchApp(info: ResolveInfo) {
        RootWindowBridge(this).launchFreeform(info) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(info.activityInfo.packageName, info.activityInfo.name)
            try { startActivity(intent) } catch (_: Exception) { toast("Unable to launch application") }
        }
    }

    private fun openTerminalWindow() {
        Thread {
            val result = RootWindowBridge(this).runRoot(
                "settings put global enable_freeform_support 1; " +
                    "settings put global force_resizable_activities 1; " +
                    "am start --windowingMode 5 -n com.mt.gnome/.TerminalActivity"
            )
            val started = result.contains("Starting:") && result.contains("exit=0") &&
                !result.contains("Error:") && !result.contains("Exception")
            runOnUiThread {
                if (!started) {
                    try { startActivity(Intent(this, TerminalActivity::class.java)) }
                    catch (_: Exception) { toast("Could not open terminal") }
                }
            }
        }.start()
    }

    private fun focusSearch() {
        if (!::search.isInitialized) return
        search.requestFocus()
        search.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showQuickSettings() {
        val names = arrayOf("Enable Linux-style app windows", "System settings", "Wi-Fi", "Bluetooth", "Display", "Notifications")
        AlertDialog.Builder(this).setTitle("MT desktop controls").setItems(names) { _, which ->
            if (which == 0) {
                enableLinuxWindows()
                return@setItems
            }
            val action = when (which) {
                2 -> Settings.ACTION_WIFI_SETTINGS
                3 -> Settings.ACTION_BLUETOOTH_SETTINGS
                4 -> Settings.ACTION_DISPLAY_SETTINGS
                5 -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            try { startActivity(Intent(action)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }.show()
    }

    private fun showFirstRunSetupIfNeeded() {
        if (prefs.getBoolean("setup_seen", false)) return
        desktop.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            AlertDialog.Builder(this)
                .setTitle("Set up MT-GNOME desktop")
                .setMessage(
                    "MT-GNOME does not need ordinary Android runtime permissions for its current features. " +
                    "To enable Linux-style floating app windows, it needs your approval for root access in Magisk. " +
                    "Android/Samsung still controls each app's actual window frame and may restrict freeform mode."
                )
                .setPositiveButton("Enable desktop windows") { _, _ ->
                    prefs.edit().putBoolean("setup_seen", true).apply()
                    enableLinuxWindows()
                }
                .setNegativeButton("Not now") { _, _ ->
                    prefs.edit().putBoolean("setup_seen", true).apply()
                }
                .setOnCancelListener { prefs.edit().putBoolean("setup_seen", true).apply() }
                .show()
        }, 450)
    }

    private fun enableLinuxWindows() {
        Thread {
            val result = RootWindowBridge(this).runRoot(
                "settings put global enable_freeform_support 1 && " +
                "settings put global force_resizable_activities 1"
            )
            runOnUiThread {
                val success = result.contains("exit=0")
                AlertDialog.Builder(this)
                    .setTitle(if (success) "Desktop window mode enabled" else "Root access required")
                    .setMessage(
                        if (success) "Android accepted the freeform/resizable window settings. " +
                            "Open apps from MT-GNOME to try floating windows. Samsung firmware may still restrict some apps."
                        else "Approve MT-GNOME in the Magisk prompt, then try again.\n\n$result"
                    )
                    .setPositiveButton("OK", null)
                    .show()
                updateRootStatus()
            }
        }.start()
    }

    private fun showAppearance() {
        val options = arrayOf("Change wallpaper", "Screen orientation", "Display resolution")
        AlertDialog.Builder(this).setTitle("Desktop settings").setItems(options) { _, which ->
            when (which) {
                0 -> showWallpaperPicker()
                1 -> showOrientationSettings()
                2 -> showResolutionSettings()
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun showWallpaperPicker() {
        val wallpapers = arrayOf("Aurora", "Emerald", "Midnight", "Sunset", "Arctic")
        val selected = wallpapers.indexOf(wallpaperName).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Choose desktop wallpaper")
            .setSingleChoiceItems(wallpapers, selected) { dialog, which ->
                wallpaperName = wallpapers[which]
                prefs.edit()
                    .putString("wallpaper", wallpaperName)
                    .putString("theme", wallpaperName)
                    .apply()
                buildDesktop()
                renderApps()
                dialog.dismiss()
                toast("$wallpaperName wallpaper applied")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOrientationSettings() {
        val choices = arrayOf("Landscape (default)", "Follow device setting", "Portrait")
        val current = prefs.getString("orientation", "landscape") ?: "landscape"
        val selected = when (current) { "system" -> 1; "portrait" -> 2; else -> 0 }
        AlertDialog.Builder(this).setTitle("Display orientation")
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                val value = when (which) { 1 -> "system"; 2 -> "portrait"; else -> "landscape" }
                prefs.edit().putString("orientation", value).apply()
                applyOrientationPreference()
                dialog.dismiss()
                toast(choices[which] + " selected")
            }.setNegativeButton("Cancel", null).show()
    }

    @Suppress("DEPRECATION")
    private fun currentDisplaySize(): Point {
        val size = Point()
        windowManager.defaultDisplay.getRealSize(size)
        return size
    }

    private fun showResolutionSettings() {
        val size = currentDisplaySize()
        val landscape = size.x >= size.y
        val width = maxOf(size.x, size.y)
        val height = minOf(size.x, size.y)
        val presets = listOf(
            "Native / reset (${width} × ${height})" to null,
            "90% (${width * 9 / 10} × ${height * 9 / 10})" to Pair(width * 9 / 10, height * 9 / 10),
            "80% (${width * 4 / 5} × ${height * 4 / 5})" to Pair(width * 4 / 5, height * 4 / 5),
            "75% (${width * 3 / 4} × ${height * 3 / 4})" to Pair(width * 3 / 4, height * 3 / 4),
            "67% (${width * 2 / 3} × ${height * 2 / 3})" to Pair(width * 2 / 3, height * 2 / 3)
        )
        AlertDialog.Builder(this).setTitle("Display resolution")
            .setMessage("Changes Android's logical display size for the whole device. Root approval is required. The physical LCD resolution cannot be changed.")
            .setItems(presets.map { it.first }.toTypedArray()) { _, which ->
                val chosen = presets[which].second
                val command = if (chosen == null) "wm size reset" else {
                    val w = if (landscape) chosen.first else chosen.second
                    val h = if (landscape) chosen.second else chosen.first
                    "wm size ${w}x${h}"
                }
                Thread {
                    val result = RootWindowBridge(this).runRoot(command)
                    runOnUiThread {
                        if (result.contains("exit=0")) {
                            toast(if (chosen == null) "Native display size restored" else "Logical resolution applied")
                            desktop.postDelayed({ updateResolution(); buildDesktop(); renderApps() }, 500)
                        } else {
                            AlertDialog.Builder(this).setTitle("Could not change resolution")
                                .setMessage("Allow root access to MT-GNOME in Magisk and retry.\n\n$result")
                                .setPositiveButton("OK", null).show()
                        }
                    }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun applyOrientationPreference() {
        requestedOrientation = when (prefs.getString("orientation", "landscape")) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "system" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun updateRootStatus() {
        Thread {
            val output = RootWindowBridge(this).runRoot("id")
            runOnUiThread {
                if (::rootStatus.isInitialized) {
                    val ok = output.contains("uid=0")
                    rootStatus.text = if (ok) "●  ROOT ACCESS AVAILABLE" else "○  ROOT ACCESS NOT AUTHORISED"
                    rootStatus.setTextColor(if (ok) Color.rgb(117, 224, 184) else muted)
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun updateResolution() {
        if (!::resolution.isInitialized) return
        val size = Point()
        windowManager.defaultDisplay.getRealSize(size)
        resolution.text = "${size.x} × ${size.y}  ⚙"
        resolution.contentDescription = "Current logical resolution. Tap to change."
        resolution.setOnClickListener { showResolutionSettings() }
    }

    private fun updateClock() { if (::clock.isInitialized) clock.text = now() }
    private fun now() = SimpleDateFormat("EEE  d MMM  •  HH:mm", Locale.getDefault()).format(Date())

    private fun addDockButton(dock: LinearLayout, glyph: String, caption: String, action: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { action() }
            isClickable = true
            background = rounded(Color.argb(45, 210, 210, 255), 12)
        }
        item.addView(label(glyph, 22, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        item.addView(label(caption, 8, muted, true).apply { gravity = Gravity.CENTER })
        val lp = LinearLayout.LayoutParams(-1, dp(58))
        lp.bottomMargin = dp(8)
        dock.addView(item, lp)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.argb(35, 220, 220, 255))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}

private class RootWindowBridge(private val activity: Activity) {
    fun launchFreeform(info: ResolveInfo, fallback: () -> Unit) {
        val component = info.activityInfo.packageName + "/" + info.activityInfo.name
        Thread {
            val command = "settings put global enable_freeform_support 1 && " +
                "settings put global force_resizable_activities 1 && " +
                "am start --windowingMode 5 -n " + shellQuote(component)
            val result = runRoot(command)
            val launched = result.contains("Starting:") && !result.contains("Error:") &&
                !result.contains("Exception") && result.contains("exit=0")
            activity.runOnUiThread {
                if (launched) Toast.makeText(activity, "Opening in a floating window", Toast.LENGTH_SHORT).show()
                else {
                    Toast.makeText(activity, "Freeform blocked or root not approved. Opening normally.", Toast.LENGTH_LONG).show()
                    fallback()
                }
            }
        }.start()
    }
    fun runRoot(command: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        output + "\n" + errors + "\nexit=" + exitCode
    } catch (error: Exception) { error.toString() }
    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
