package com.mt.gnome

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.Button
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
    private val wallpaperRequest = 4102

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
        if (intent.getBooleanExtra("open_settings", false)) desktop.post { showAppearance() }
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
        if (android.provider.Settings.canDrawOverlays(this)) {
            try { startService(Intent(this, DesktopDockService::class.java)) } catch (_: Exception) {}
        }
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

    // Nature photographs are bundled into the APK, so wallpaper switching works offline.
    private fun wallpaperBackground(): Drawable {
        val custom = prefs.getString("wallpaper_uri", null)
        if (!custom.isNullOrBlank()) { try { return android.graphics.drawable.Drawable.createFromStream(contentResolver.openInputStream(Uri.parse(custom)), null) ?: GradientDrawable() } catch (_: Exception) {} }
        val imageName = when (wallpaperName) {
            "Forest" -> "forest.jpg"
            "Mountain Peaks" -> "mountain_peaks.jpg"
            "Tropical Beach" -> "tropical_beach.jpg"
            "Open Landscape" -> "open_landscape.jpg"
            else -> "mountain_lake.jpg"
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open("wallpapers/$imageName").use { BitmapFactory.decodeStream(it, null, bounds) }
            val targetW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val targetH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = assets.open("wallpapers/$imageName").use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalStateException("Wallpaper image could not be decoded")
            BitmapDrawable(resources, bitmap).apply { gravity = Gravity.FILL }
        } catch (_: Exception) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                Color.rgb(35, 55, 78), Color.rgb(12, 24, 42)
            ))
        }
    }

    private fun buildDesktop() {
        desktop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = wallpaperBackground()
        }
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            background = rounded(Color.argb(238, 20, 22, 29), 20)
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        addDockButton(dock, "⌘", "Terminal") { openTerminalWindow() }
        addDockButton(dock, "⚙", "Settings") { showAppearance() }
        addDockButton(dock, "▣", "System") { showQuickSettings() }
        dock.addView(View(this).apply { setBackgroundColor(Color.argb(80,255,255,255)) },
            LinearLayout.LayoutParams(dp(38), dp(1)).apply { topMargin=dp(4); bottomMargin=dp(7) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        apps.forEach { info ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(2), dp(6), dp(2), dp(6))
                setOnClickListener { launchApp(info) }
                contentDescription = info.loadLabel(packageManager).toString()
            }
            item.addView(ImageView(this).apply { setImageDrawable(info.loadIcon(packageManager)); scaleType=ImageView.ScaleType.FIT_CENTER },
                LinearLayout.LayoutParams(dp(34),dp(34)))
            item.addView(label(info.loadLabel(packageManager).toString(),8,Color.WHITE,true).apply {
                gravity=Gravity.CENTER; maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(54),dp(16)))
            list.addView(item, LinearLayout.LayoutParams(dp(56),dp(58)))
        }
        val scroll = ScrollView(this).apply { overScrollMode=View.OVER_SCROLL_NEVER }
        scroll.addView(list)
        dock.addView(scroll, LinearLayout.LayoutParams(dp(58),0,1f))
        desktop.addView(dock, LinearLayout.LayoutParams(dp(70),-1))
        setContentView(desktop)
    }

    private fun loadApps() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
        buildDesktop()
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
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=rounded(Color.rgb(27,29,38),20); setPadding(dp(16),dp(14),dp(16),dp(14)) }
        val heading = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        heading.addView(label("MT-GNOME Settings",19,Color.WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(label("✕",20,muted,true).apply { setOnClickListener { dialog.dismiss() } })
        root.addView(heading)
        root.addView(label("Desktop configuration",10,muted).apply { setPadding(0,dp(4),0,dp(10)) })
        val body=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val nav=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=rounded(Color.rgb(36,38,49),12); setPadding(dp(4),dp(4),dp(4),dp(4)) }
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        val scroll=ScrollView(this).apply { isFillViewport=true }; scroll.addView(panel)
        body.addView(nav,LinearLayout.LayoutParams(dp(108),-1)); body.addView(scroll,LinearLayout.LayoutParams(0,-1,1f))
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        fun page(title:String,desc:String) { panel.removeAllViews(); panel.addView(label(title,17,Color.WHITE,true)); panel.addView(label(desc,10,muted).apply { setPadding(0,dp(3),0,dp(12)) }) }
        fun item(title:String,desc:String="",click:()->Unit) {
            val v=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=rounded(Color.rgb(40,43,55),10); setPadding(dp(10),dp(9),dp(10),dp(9)); setOnClickListener { click() } }
            v.addView(label(title,12,Color.WHITE,true))
            if(desc.isNotBlank()) v.addView(label(desc,9,muted).apply { setPadding(0,dp(3),0,0) })
            panel.addView(v,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(7) })
        }
        fun navItem(name:String,open:()->Unit) { nav.addView(label(name,10,Color.WHITE,true).apply { setPadding(dp(7),dp(11),dp(3),dp(11)); setOnClickListener { open() } }) }
        navItem("Appearance") {
            page("Appearance","Wallpaper and desktop presentation")
            listOf("Mountain Lake","Forest","Mountain Peaks","Tropical Beach","Open Landscape").forEach { name ->
                item(name,if(wallpaperName==name) "Built-in wallpaper" else "") {
                    wallpaperName=name; prefs.edit().putString("wallpaper",name).remove("wallpaper_uri").apply(); buildDesktop(); dialog.dismiss()
                }
            }
            item("Choose from phone storage","Select an image from internal storage or SD card") {
                val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
                startActivityForResult(pick,wallpaperRequest)
            }
        }
        navItem("Input & Pointer") {
            page("Input & Pointer","Touch and pointer preferences")
            item("Direct touch","Use direct taps and drags") { prefs.edit().putString("input_mode","direct").apply(); toast("Direct touch selected") }
            item("Trackpad mode","Save relative pointer preference") { prefs.edit().putString("input_mode","trackpad").apply(); toast("Trackpad preference saved") }
            item("Overlay permission","Allow the dock or pointer to appear above other apps") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
            item("Accessibility settings","Android requires explicit approval for cross-app gesture control") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        navItem("Windows") {
            page("Windows","Floating app window behaviour")
            item("Enable freeform windows","Uses Magisk root; firmware may restrict some apps") { dialog.dismiss(); enableLinuxWindows() }
            item("Display over other apps","Manage Android overlay access") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
        }
        navItem("Terminal") {
            page("Terminal","Terminal appearance and behaviour")
            item("Font size","Current: ${prefs.getInt("terminal_font_size",13)} sp") {
                val sizes=arrayOf("11 sp","12 sp","13 sp","14 sp","15 sp","16 sp")
                AlertDialog.Builder(this).setTitle("Terminal font size").setItems(sizes) { _,i -> prefs.edit().putInt("terminal_font_size",11+i).apply() }.show()
            }
            item("Default distribution",prefs.getString("active_distro","Select when opening") ?: "Select when opening") { toast("Choose the active distribution in DISTROS") }
        }
        navItem("Display") {
            page("Display","Screen configuration")
            item("Orientation",prefs.getString("orientation","landscape") ?: "landscape") { showOrientationSettings() }
            item("Logical resolution","Android display size; physical panel is unchanged") { showResolutionSettings() }
        }
        navItem("System & Security") {
            page("System & Security","App permissions and device integration")
            item("Root access","Authorize or verify Magisk integration") { enableLinuxWindows() }
            item("Android app permissions","Review permissions in Android settings") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))) }
            item("System settings","Open Android settings") { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        navItem("About") { page("About MT-GNOME","MT-OS desktop shell"); item("Version","0.1.0-alpha") {}; item("Device","${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}") {} }
        page("Appearance","Wallpaper and desktop presentation")
        root.addView(Button(this).apply { text="Close"; isAllCaps=false; setOnClickListener { dialog.dismiss() } },LinearLayout.LayoutParams(-1,dp(42)).apply { topMargin=dp(10) })
        dialog.setContentView(root); dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels*.94f).toInt(),(resources.displayMetrics.heightPixels*.88f).toInt())
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes=dialog.window?.attributes?.apply { gravity=Gravity.CENTER; dimAmount=.48f }
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==wallpaperRequest && resultCode==RESULT_OK) {
            val uri=data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.edit().putString("wallpaper_uri",uri.toString()).apply()
                buildDesktop(); toast("Selected wallpaper applied")
            } catch(e:Exception) { toast("Could not apply wallpaper: ${e.message}") }
        }
    }

    private fun showWallpaperPicker() {
        val wallpapers = arrayOf("Mountain Lake", "Forest", "Mountain Peaks", "Tropical Beach", "Open Landscape")
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
