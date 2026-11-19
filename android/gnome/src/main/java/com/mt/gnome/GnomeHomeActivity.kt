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
import android.widget.HorizontalScrollView
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
    private val muted = Color.rgb(153, 165, 190)
    private lateinit var desktop: LinearLayout
    private lateinit var appGrid: GridLayout
    private lateinit var search: EditText
    private lateinit var clock: TextView
    private lateinit var rootStatus: TextView
    private lateinit var categoryBar: LinearLayout
    private var apps: List<ResolveInfo> = emptyList()
    private var category = "All"
    private var themeName = "Aurora"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeName = prefs.getString("theme", "Aurora") ?: "Aurora"
        window.statusBarColor = Color.rgb(12, 16, 31)
        window.navigationBarColor = Color.rgb(12, 16, 31)
        buildDesktop()
        loadApps()
        updateRootStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::clock.isInitialized) updateClock()
        if (::rootStatus.isInitialized) updateRootStatus()
    }

    private fun palette(): IntArray = when (themeName) {
        "Emerald" -> intArrayOf(Color.rgb(7, 65, 67), Color.rgb(13, 35, 54), Color.rgb(12, 16, 31))
        "Midnight" -> intArrayOf(Color.rgb(38, 49, 83), Color.rgb(22, 30, 55), Color.rgb(12, 16, 31))
        else -> intArrayOf(Color.rgb(55, 39, 112), Color.rgb(28, 39, 78), Color.rgb(12, 16, 31))
    }

    private fun buildDesktop() {
        desktop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette())
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panel()
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val mark = TextView(this).apply {
            text = "M"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(151, 112, 255), Color.rgb(67, 151, 245))).apply {
                cornerRadius = dp(12).toFloat()
            }
        }
        top.addView(mark, LinearLayout.LayoutParams(dp(36), dp(36)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        brand.addView(label("MT-OS", 14, Color.WHITE, true))
        brand.addView(label("DESKTOP ENVIRONMENT", 8, muted, true))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        clock = label(now(), 11, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        top.addView(clock)
        top.addView(label("⋮", 23, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { showQuickSettings() }
        }, LinearLayout.LayoutParams(dp(28), dp(36)))
        desktop.addView(top, LinearLayout.LayoutParams(-1, dp(56)))

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(23), dp(3), dp(16))
        }
        hero.addView(label("YOUR PERSONAL WORKSPACE", 10, muted, true))
        hero.addView(label("Everything in its place.", 26, Color.WHITE, true).apply {
            setPadding(0, dp(4), 0, dp(3))
        })
        hero.addView(label("Launch applications, manage windows and make it yours.", 11, muted))
        desktop.addView(hero)

        search = EditText(this).apply {
            hint = "Search applications"
            textSize = 14f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Color.argb(225, 34, 42, 72), 15)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderApps() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        desktop.addView(search, LinearLayout.LayoutParams(-1, dp(48)))

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(17), dp(2), dp(8))
        }
        section.addView(label("APPLICATIONS", 11, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(label("HOLD AN APP FOR OPTIONS", 8, muted, true))
        desktop.addView(section)

        val categoriesScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        categoryBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        refreshCategories()
        categoriesScroll.addView(categoryBar)
        desktop.addView(categoriesScroll, LinearLayout.LayoutParams(-1, dp(42)))

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(6))
        }
        appGrid = GridLayout(this).apply { columnCount = if (resources.configuration.screenWidthDp > 600) 4 else 3 }
        scroll.addView(appGrid)
        desktop.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panel()
            setPadding(dp(12), dp(9), dp(12), dp(8))
        }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addDockButton(dock, "⌂", "Home") { category = "All"; search.setText(""); refreshCategories() }
        addDockButton(dock, "▣", "Windows") { showWindows() }
        addDockButton(dock, "▦", "Calculator") { showCalculator() }
        addDockButton(dock, "⌕", "Find") {
            search.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
        addDockButton(dock, "⚙", "Settings") { showAppearance() }
        bottom.addView(dock, LinearLayout.LayoutParams(-1, dp(47)))
        rootStatus = label("Checking system access…", 9, muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, 0)
        }
        bottom.addView(rootStatus)
        desktop.addView(bottom, LinearLayout.LayoutParams(-1, -2))
        setContentView(desktop)
        updateClock()
    }

    private fun refreshCategories() {
        categoryBar.removeAllViews()
        listOf("All", "Internet", "Media", "Tools", "System").forEach { name ->
            val selected = name == category
            val chip = label(name, 11, if (selected) Color.WHITE else muted, selected).apply {
                gravity = Gravity.CENTER
                setPadding(dp(15), dp(8), dp(15), dp(8))
                background = rounded(
                    if (selected) Color.argb(220, 115, 101, 224) else Color.argb(90, 30, 39, 67),
                    18
                )
                setOnClickListener {
                    category = name
                    refreshCategories()
                    renderApps()
                }
            }
            categoryBar.addView(chip, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(7) })
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
            val match = when (category) {
                "Internet" -> listOf("browser", "chrome", "firefox", "web").any { pkg.contains(it) }
                "Media" -> listOf("camera", "music", "video", "gallery").any { pkg.contains(it) }
                "Tools" -> listOf("calc", "clock", "file", "notes").any { pkg.contains(it) }
                "System" -> pkg.contains("settings") || pkg.contains("system")
                else -> true
            }
            title.contains(term, true) && match
        }
        results.forEach { info ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(11), dp(5), dp(8))
                background = rounded(Color.argb(72, 120, 137, 196), 17)
                setOnClickListener { launchApp(info) }
                setOnLongClickListener { showAppActions(info); true }
            }
            cell.addView(ImageView(this).apply {
                setImageDrawable(info.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            cell.addView(label(info.loadLabel(packageManager).toString(), 10, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(7), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(34)))
            appGrid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(104)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        if (results.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(30), dp(12), dp(30))
                addView(label("No applications found", 14, Color.WHITE, true))
                addView(label("Try another search or category.", 11, muted))
            }
            appGrid.addView(empty, GridLayout.LayoutParams().apply {
                columnSpec = GridLayout.spec(0, appGrid.columnCount)
            })
        }
    }

    private fun launchApp(info: ResolveInfo) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(info.activityInfo.packageName, info.activityInfo.name)
        try { startActivity(intent) } catch (_: Exception) { toast("Unable to launch application") }
    }

    private fun showAppActions(info: ResolveInfo) {
        AlertDialog.Builder(this).setTitle(info.loadLabel(packageManager))
            .setItems(arrayOf("Open application", "Open in floating window · root")) { _, which ->
                if (which == 0) launchApp(info) else RootWindowBridge(this).launchFreeform(info)
            }.show()
    }

    private fun showCalculator() {
        val display = TextView(this).apply {
            text = "0"
            textSize = 34f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.rgb(24, 31, 56), 16)
            minHeight = dp(82)
        }
        var expression = ""
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(30, 35, 66), Color.rgb(15, 20, 39))).apply {
                cornerRadius = dp(22).toFloat()
            }
        }
        layout.addView(label("MT-OS CALCULATOR", 10, muted, true).apply {
            setPadding(dp(4), dp(3), 0, dp(12))
        })
        layout.addView(display, LinearLayout.LayoutParams(-1, dp(82)))
        val keys = arrayOf(
            arrayOf("C", "⌫", "(", "÷"),
            arrayOf("7", "8", "9", "×"),
            arrayOf("4", "5", "6", "−"),
            arrayOf("1", "2", "3", "+"),
            arrayOf("%", "0", ".", "=")
        )
        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(0, dp(12), 0, 0)
        }
        fun refresh() { display.text = if (expression.isEmpty()) "0" else expression }
        keys.forEach { row ->
            row.forEach { key ->
                val special = key in listOf("÷", "×", "−", "+", "=")
                val action = TextView(this).apply {
                    text = key
                    textSize = if (key.length > 1) 17f else 21f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    background = rounded(
                        when {
                            key == "=" -> Color.rgb(111, 96, 224)
                            special -> Color.rgb(55, 62, 105)
                            key == "C" -> Color.rgb(125, 55, 83)
                            else -> Color.argb(180, 45, 53, 82)
                        }, 14
                    )
                    setOnClickListener {
                        when (key) {
                            "C" -> expression = ""
                            "⌫" -> if (expression.isNotEmpty()) expression = expression.dropLast(1)
                            "=" -> try {
                                val result = CalculatorParser(expression).parse()
                                expression = if (result.isFinite()) {
                                    if (result == result.toLong().toDouble()) result.toLong().toString()
                                    else String.format(Locale.US, "%.10f", result).trimEnd('0').trimEnd('.')
                                } else "Error"
                            } catch (_: Exception) { expression = "Error" }
                            else -> {
                                if (expression == "Error") expression = ""
                                expression += when (key) {
                                    "÷" -> "/"
                                    "×" -> "*"
                                    "−" -> "-"
                                    else -> key
                                }
                            }
                        }
                        refresh()
                    }
                }
                grid.addView(action, GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(54)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                })
            }
        }
        layout.addView(grid)
        AlertDialog.Builder(this)
            .setView(layout)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showWindows() {
        AlertDialog.Builder(this).setTitle("Window management")
            .setMessage("Request Android freeform mode for compatible applications. Samsung firmware may still restrict floating windows. Root access is used only after you choose to enable this feature.")
            .setPositiveButton("Enable freeform") { _, _ -> RootWindowBridge(this).enableFreeform() }
            .setNegativeButton("Close", null).show()
    }

    private fun showQuickSettings() {
        val names = arrayOf("Android settings", "Wi-Fi", "Bluetooth", "Display", "Notifications")
        AlertDialog.Builder(this).setTitle("System shortcuts")
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
        val options = arrayOf("Aurora  ·  violet / blue", "Emerald  ·  teal / deep green", "Midnight  ·  navy / slate")
        AlertDialog.Builder(this).setTitle("Personalisation")
            .setItems(options) { _, which ->
                themeName = when (which) { 1 -> "Emerald"; 2 -> "Midnight"; else -> "Aurora" }
                prefs.edit().putString("theme", themeName).apply()
                buildDesktop()
                renderApps()
                toast("$themeName theme applied")
            }
            .setNeutralButton("Window management") { _, _ -> showWindows() }
            .setNegativeButton("Close", null).show()
    }

    private fun updateRootStatus() {
        Thread {
            val output = RootWindowBridge(this).runRoot("id")
            runOnUiThread {
                rootStatus.text = if (output.contains("uid=0")) "●  ROOT ACCESS AVAILABLE" else "○  ROOT ACCESS NOT AUTHORISED"
                rootStatus.setTextColor(if (output.contains("uid=0")) Color.rgb(117, 224, 184) else muted)
            }
        }.start()
    }

    private fun updateClock() { if (::clock.isInitialized) clock.text = now() }
    private fun now() = SimpleDateFormat("EEE, d MMM  ·  HH:mm", Locale.getDefault()).format(Date())

    private fun addDockButton(dock: LinearLayout, glyph: String, caption: String, action: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }
        item.addView(label(glyph, 19, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        item.addView(label(caption, 9, muted, true).apply { gravity = Gravity.CENTER })
        dock.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun panel() = rounded(Color.argb(244, 13, 19, 39), 19)
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.argb(45, 180, 190, 255))
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
                    "Root commands completed. A reboot may be required. Samsung firmware and individual app compatibility determine whether freeform windows work."
                } else {
                    "Root was not granted. Approve MT-OS Desktop in Magisk and try again.\n" + result
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

private class CalculatorParser(private val input: String) {
    private var position = 0

    fun parse(): Double {
        if (input.isBlank()) throw IllegalArgumentException("Empty expression")
        val value = expression()
        skipSpaces()
        if (position != input.length) throw IllegalArgumentException("Unexpected input")
        return value
    }

    private fun expression(): Double {
        var value = term()
        while (true) {
            skipSpaces()
            value = when {
                take('+') -> value + term()
                take('-') -> value - term()
                else -> return value
            }
        }
    }

    private fun term(): Double {
        var value = factor()
        while (true) {
            skipSpaces()
            value = when {
                take('*') -> value * factor()
                take('/') -> {
                    val divisor = factor()
                    if (divisor == 0.0) throw ArithmeticException("Division by zero")
                    value / divisor
                }
                take('%') -> value % factor()
                else -> return value
            }
        }
    }

    private fun factor(): Double {
        skipSpaces()
        if (take('+')) return factor()
        if (take('-')) return -factor()
        if (take('(')) {
            val value = expression()
            skipSpaces()
            if (!take(')')) throw IllegalArgumentException("Missing closing bracket")
            return value
        }
        val start = position
        var dots = 0
        while (position < input.length && (input[position].isDigit() || input[position] == '.')) {
            if (input[position] == '.') dots++
            if (dots > 1) throw IllegalArgumentException("Invalid number")
            position++
        }
        if (start == position) throw IllegalArgumentException("Number expected")
        return input.substring(start, position).toDouble()
    }

    private fun take(char: Char): Boolean {
        if (position < input.length && input[position] == char) {
            position++
            return true
        }
        return false
    }

    private fun skipSpaces() {
        while (position < input.length && input[position].isWhitespace()) position++
    }
}
