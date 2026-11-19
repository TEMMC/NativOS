package com.mt.gnome

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
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
    private val muted = Color.rgb(183, 192, 220)
    private lateinit var desktop: LinearLayout
    private lateinit var appGrid: GridLayout
    private lateinit var search: EditText
    private lateinit var clock: TextView
    private lateinit var rootStatus: TextView
    private var apps: List<ResolveInfo> = emptyList()
    private var category = "All"
    private var themeName = "Aurora"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeName = prefs.getString("theme", "Aurora") ?: "Aurora"
        window.statusBarColor = Color.rgb(9, 11, 26)
        window.navigationBarColor = Color.rgb(9, 11, 26)
        buildDesktop()
        loadApps()
        updateRootStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::rootStatus.isInitialized) updateClock()
    }

    private fun buildDesktop() {
        val gradient = when (themeName) {
            "Emerald" -> intArrayOf(Color.rgb(5, 46, 52), Color.rgb(12, 23, 45), Color.rgb(9, 11, 26))
            "Midnight" -> intArrayOf(Color.rgb(20, 30, 58), Color.rgb(13, 17, 38), Color.rgb(7, 9, 20))
            else -> intArrayOf(Color.rgb(43, 20, 87), Color.rgb(18, 28, 62), Color.rgb(9, 11, 26))
        }
        desktop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(9))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, gradient)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(235, 8, 11, 28), 22)
            setPadding(dp(14), dp(5), dp(12), dp(5))
        }
        val activities = label("✣  Activities", 14, Color.WHITE, true).apply {
            setOnClickListener { showActivities() }
        }
        clock = label(now(), 12, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val quick = label("⌁  ◉  ⋮", 19, Color.WHITE, true).apply {
            gravity = Gravity.END
            setOnClickListener { showQuickSettings() }
        }
        top.addView(activities, LinearLayout.LayoutParams(0, dp(36), 1f))
        top.addView(clock, LinearLayout.LayoutParams(0, dp(36), 1f))
        top.addView(quick, LinearLayout.LayoutParams(dp(82), dp(36)))
        desktop.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(19), dp(5), dp(13))
        }
        hero.addView(label("MT-GNOME", 28, Color.WHITE, true))
        hero.addView(label("A futuristic Linux-inspired desktop for Android", 12, muted))
        desktop.addView(hero)

        search = EditText(this).apply {
            hint = "⌕   Search applications"
            textSize = 14f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Color.argb(225, 43, 42, 79), 18)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderApps() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        desktop.addView(search, LinearLayout.LayoutParams(-1, dp(46)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(10))
        }
        val categories = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(205, 20, 24, 49), 19)
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        listOf("All", "Internet", "Media", "Tools", "System").forEach { name ->
            val item = label((if (name == category) "●  " else "○  ") + name, 12,
                if (name == category) Color.WHITE else muted, name == category)
            item.setPadding(dp(7), dp(12), dp(5), dp(12))
            item.setOnClickListener {
                category = name
                renderApps()
                updateCategoryLabels(categories)
            }
            categories.addView(item)
        }
        content.addView(categories, LinearLayout.LayoutParams(dp(101), -1))

        val scroll = ScrollView(this)
        appGrid = GridLayout(this).apply { columnCount = if (resources.configuration.screenWidthDp > 600) 4 else 3 }
        scroll.addView(appGrid)
        content.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        desktop.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.argb(242, 10, 13, 32), 23)
            setPadding(dp(6), dp(7), dp(6), dp(7))
        }
        addDockButton(dock, "▦", "Apps") { showActivities() }
        addDockButton(dock, "▣", "Windows") { showWindows() }
        addDockButton(dock, "⌕", "Search") {
            search.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
        addDockButton(dock, "⚙", "Settings") { showAppearance() }
        desktop.addView(dock, LinearLayout.LayoutParams(-1, dp(57)))

        rootStatus = label("Checking root access…", 10, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        desktop.addView(rootStatus)
        setContentView(desktop)
        updateClock()
    }

    private fun updateCategoryLabels(container: LinearLayout) {
        for (i in 0 until container.childCount) {
            val view = container.getChildAt(i) as? TextView ?: continue
            val name = listOf("All", "Internet", "Media", "Tools", "System")[i]
            view.text = (if (name == category) "●  " else "○  ") + name
            view.setTextColor(if (name == category) Color.WHITE else muted)
        }
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
        val results = apps.filter { info ->
            val title = info.loadLabel(packageManager).toString()
            val pkg = info.activityInfo.packageName.lowercase(Locale.ROOT)
            val categoryMatch = when (category) {
                "Internet" -> listOf("browser", "chrome", "firefox", "web").any { pkg.contains(it) }
                "Media" -> listOf("camera", "music", "video", "gallery").any { pkg.contains(it) }
                "Tools" -> listOf("calc", "clock", "file", "notes").any { pkg.contains(it) }
                "System" -> pkg.contains("settings") || pkg.contains("system")
                else -> true
            }
            title.contains(term, true) && categoryMatch
        }
        results.forEach { info ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(9), dp(3), dp(7))
                background = rounded(Color.argb(48, 132, 148, 255), 15)
                setOnClickListener { launchApp(info) }
                setOnLongClickListener { showAppActions(info); true }
            }
            val icon = ImageView(this).apply {
                setImageDrawable(info.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            cell.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
            cell.addView(label(info.loadLabel(packageManager).toString(), 10, Color.WHITE).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(34)))
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(94)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            appGrid.addView(cell, params)
        }
        if (results.isEmpty()) appGrid.addView(label("No matching applications", 13, muted))
    }

    private fun launchApp(info: ResolveInfo) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
        try { startActivity(intent) } catch (_: Exception) { toast("Unable to launch application") }
    }

    private fun showAppActions(info: ResolveInfo) {
        AlertDialog.Builder(this).setTitle(info.loadLabel(packageManager))
            .setItems(arrayOf("Open", "Open in floating window · root")) { _, which ->
                if (which == 0) launchApp(info) else RootWindowBridge(this).launchFreeform(info)
            }.show()
    }

    private fun showActivities() {
        val options = arrayOf("All applications", "Window overview", "Appearance and themes", "Android system settings")
        AlertDialog.Builder(this).setTitle("Activities")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { category = "All"; search.setText("") }
                    1 -> showWindows()
                    2 -> showAppearance()
                    3 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }.show()
    }

    private fun showWindows() {
        AlertDialog.Builder(this).setTitle("Windows and workspaces")
            .setMessage("MT-GNOME can request Android freeform mode for compatible applications. Samsung firmware may still restrict floating windows. Root is used only when you choose an action.")
            .setPositiveButton("Enable freeform") { _, _ -> RootWindowBridge(this).enableFreeform() }
            .setNeutralButton("Developer options") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
            .setNegativeButton("Close", null).show()
    }

    private fun showQuickSettings() {
        val names = arrayOf("Android settings", "Wi-Fi", "Bluetooth", "Display", "Notifications")
        AlertDialog.Builder(this).setTitle("Quick settings")
            .setItems(names) { _, which ->
                val action = when (which) {
                    1 -> Settings.ACTION_WIFI_SETTINGS
                    2 -> Settings.ACTION_BLUETOOTH_SETTINGS
                    3 -> Settings.ACTION_DISPLAY_SETTINGS
                    4 -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                try { startActivity(Intent(action)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }.show()
    }

    private fun showAppearance() {
        val options = arrayOf("Aurora · violet and electric blue", "Emerald · teal and midnight", "Midnight · deep navy")
        AlertDialog.Builder(this).setTitle("MT-GNOME themes")
            .setItems(options) { _, which ->
                themeName = when (which) { 1 -> "Emerald"; 2 -> "Midnight"; else -> "Aurora" }
                prefs.edit().putString("theme", themeName).apply()
                buildDesktop()
                renderApps()
                toast(themeName + " theme applied")
            }
            .setNeutralButton("Window management") { _, _ -> showWindows() }
            .setNegativeButton("Close", null).show()
    }

    private fun updateRootStatus() {
        Thread {
            val output = RootWindowBridge(this).runRoot("id")
            runOnUiThread {
                rootStatus.text = if (output.contains("uid=0")) "MAGISK ROOT  ·  CONNECTED"
                else "ROOT NOT GRANTED  ·  approve MT-GNOME in Magisk when prompted"
            }
        }.start()
    }

    private fun updateClock() { if (::clock.isInitialized) clock.text = now() }
    private fun now() = SimpleDateFormat("EEE  d MMM   HH:mm", Locale.getDefault()).format(Date())

    private fun addDockButton(dock: LinearLayout, glyph: String, caption: String, action: () -> Unit) {
        val button = label(glyph + "\n" + caption, 11, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }
        dock.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.argb(55, 160, 170, 255))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}

private class RootWindowBridge(private val activity: Activity) {
    fun enableFreeform() {
        Thread {
            val result = runRoot("settings put global enable_freeform_support 1; settings put global force_resizable_activities 1")
            activity.runOnUiThread {
                val message = if (result.contains("uid=0")) {
                    "Root commands completed. A reboot may be required. Samsung firmware and individual app compatibility determine whether freeform windows actually work."
                } else {
                    "Root was not granted. Approve MT-GNOME in Magisk and try again.\n" + result
                }
                AlertDialog.Builder(activity).setTitle("Freeform configuration")
                    .setMessage(message).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    fun launchFreeform(info: ResolveInfo) {
        val component = info.activityInfo.packageName + "/" + info.activityInfo.name
        Thread {
            val output = runRoot("am start --windowingMode 5 -n " + shellQuote(component))
            activity.runOnUiThread {
                val failed = output.contains("Error") || output.contains("Exception")
                Toast.makeText(activity,
                    if (failed) "Floating launch failed: " + output.take(100) else "Floating launch requested",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    fun runRoot(command: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        process.waitFor()
        output + "\n" + errors
    } catch (error: Exception) {
        error.toString()
    }

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
