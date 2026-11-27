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
    private val muted = Color.rgb(174, 180, 199)
    private lateinit var desktop: LinearLayout
    private lateinit var appGrid: GridLayout
    private lateinit var search: EditText
    private lateinit var clock: TextView
    private lateinit var resolution: TextView
    private lateinit var rootStatus: TextView
    private var apps: List<ResolveInfo> = emptyList()
    private var wallpaperName = "Aurora"
    private var activeWorkspace = 1
    private val wallpaperRequest = 4102
    private data class LinuxDesktopApp(val distro: String, val name: String, val desktopId: String, val exec: String, val categories: String = "")
    private var linuxApps: List<LinuxDesktopApp> = emptyList()
    private var linuxScanRunning = false
    private val distroIds = listOf("alpine", "debian", "ubuntu", "arch", "fedora", "void", "kali", "nixos", "gentoo", "opensuse", "rocky", "openwrt")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeWorkspace = prefs.getInt("active_workspace", 1).coerceIn(1, 4)
        wallpaperName = prefs.getString("workspace_${activeWorkspace}_wallpaper", prefs.getString("wallpaper", prefs.getString("theme", "Aurora"))) ?: "Aurora"
        applyOrientationPreference()
        window.statusBarColor = themeColors()[0]
        window.navigationBarColor = themeColors()[0]
        enterDesktopMode()
        buildDesktop()
        loadApps()
        refreshLinuxApps()
        updateRootStatus()
        showFirstRunSetupIfNeeded()
        if (intent.getBooleanExtra("open_settings", false)) desktop.post { showAppearance() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        buildDesktop()
        updateResolution()
        updateRootStatus()
        refreshLinuxApps()
    }

    override fun onResume() {
        super.onResume()
        enterDesktopMode()
        if (android.provider.Settings.canDrawOverlays(this)) {
            try { startService(Intent(this, DesktopDockService::class.java)) } catch (_: Exception) {}
            if (prefs.getString("input_mode", "direct") == "trackpad") {
                try { startService(Intent(this, PointerOverlayService::class.java)) } catch (_: Exception) {}
            }
        }
        if (::clock.isInitialized) updateClock()
        if (::desktop.isInitialized) refreshLinuxApps()
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
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = wallpaperBackground()
        }

        // Desktop top panel: app menu, workspace, quick controls and live clock.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(prefs.getInt("panel_opacity", 82).coerceIn(10, 100) * 255 / 100, 18, 23, 34), 14)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        val menu = Button(this).apply {
            text = "MT  ▾"; isAllCaps = false; textSize = 12f
            setTextColor(Color.WHITE); background = rounded(themeColors()[1], 10)
            setOnClickListener { showDesktopMenu(this) }
        }
        top.addView(menu, LinearLayout.LayoutParams(-2, dp(38)))
        top.addView(label(prefs.getString("desktop_profile_name", "Desktop") ?: "Desktop", 12, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        val workspace = Button(this).apply {
            text = "Workspace $activeWorkspace"; isAllCaps = false; textSize = 10f
            setTextColor(Color.WHITE); background = rounded(Color.TRANSPARENT, 8)
            setOnClickListener { showWorkspaceSwitcher() }
        }
        if (prefs.getBoolean("show_workspace_button", true)) top.addView(workspace)
        if (prefs.getBoolean("show_top_clock", true)) {
            val clockView = label(now(), 11, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0)
            }
            clock = clockView
            top.addView(clockView)
        }
        if (prefs.getBoolean("show_top_settings", true)) {
            val quick = Button(this).apply {
                text = "⚙"; isAllCaps = false; textSize = 16f
                setTextColor(Color.WHITE); background = rounded(Color.TRANSPARENT, 8)
                setOnClickListener { showAppearance() }
            }
            top.addView(quick, LinearLayout.LayoutParams(dp(42), dp(38)))
        }
        desktop.addView(top, LinearLayout.LayoutParams(-1, dp(44)))

        // PC-style desktop: only shortcuts explicitly added by the user are placed here.
        val surface = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(8))
        }
        val section = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        section.addView(label("Workspace", 16, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(Button(this).apply {
            text = "＋ Add to desktop"; isAllCaps = false; textSize = 10f
            setTextColor(Color.WHITE); background = rounded(Color.argb(175, 36, 48, 68), 9)
            setOnClickListener { showApplicationDrawer() }
        })
        surface.addView(section)
        val showAndroidApps = prefs.getBoolean("show_android_apps", true)
        val showLinuxApps = prefs.getBoolean("show_linux_apps", true)
        val shortcuts = prefs.getStringSet("desktop_shortcuts", emptySet()) ?: emptySet()
        val hidden = prefs.getStringSet("hidden_desktop_shortcuts", emptySet()) ?: emptySet()
        appGrid = GridLayout(this).apply {
            columnCount = prefs.getInt("desktop_columns", if (resources.configuration.screenWidthDp >= 700) 6 else 4).coerceIn(3, 8)
        }
        if (showAndroidApps) apps.forEach { app ->
            val key = "android:${app.activityInfo.packageName}/${app.activityInfo.name}"
            if (shortcuts.contains(key) && !hidden.contains(key)) addAndroidDesktopIcon(appGrid, app, key)
        }
        if (showLinuxApps && prefs.getBoolean("show_linux_shortcuts", true)) linuxApps.forEach { app ->
            val key = "linux:${app.distro}:${app.desktopId}"
            if (shortcuts.contains(key) && !hidden.contains(key)) addLinuxDesktopIcon(appGrid, app, key)
        }
        val scroller = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER; addView(appGrid) }
        if (shortcuts.isEmpty()) {
            surface.addView(label("Your desktop is clean. Open Applications and choose “Add to desktop” to place shortcuts here.",
                12, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(dp(20), dp(18), dp(20), dp(18)) })
        } else {
            surface.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        if (linuxScanRunning && showLinuxApps) surface.addView(label("Discovering Linux applications…", 10, Color.LTGRAY))
        desktop.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))

        // A familiar bottom dock. Each item can be hidden or restored in Customize.
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.argb(prefs.getInt("dock_surface_opacity", 88).coerceIn(10, 100) * 255 / 100, 18, 23, 34), 16)
            setPadding(dp(7), dp(5), dp(7), dp(5))
        }
        data class DockEntry(val id: String, val glyph: String, val caption: String, val action: () -> Unit)
        val dockItems = listOf(
            DockEntry("terminal", "⌘", "Terminal") { openTerminalWindow() },
            DockEntry("files", "▤", "Files") { openInternalWindow(FileManagerActivity::class.java) },
            DockEntry("tools", "▣", "Tools") { openInternalWindow(DesktopToolsActivity::class.java) },
            DockEntry("workspaces", "▦", "Spaces") { showWorkspaceSwitcher() },
            DockEntry("customize", "✎", "Customize") { showDesktopCustomizer() }
        )
        dockItems.forEach { (id, glyph, caption, action) ->
            if (prefs.getBoolean("dock_$id", true)) addDockButton(dock, glyph, caption, action)
        }
        desktop.addView(dock, LinearLayout.LayoutParams(-1, dp(68)))
        setContentView(desktop)
    }

    private fun addAndroidDesktopIcon(grid: GridLayout, info: ResolveInfo, key: String) {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(7))
            background = rounded(Color.argb(35, 225, 232, 255), 12)
            setOnClickListener { launchApp(info) }
            setOnLongClickListener { confirmRemoveShortcut(key, info.loadLabel(packageManager).toString()); true }
        }
        cell.addView(ImageView(this).apply {
            setImageDrawable(info.loadIcon(packageManager)); scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(prefs.getInt("desktop_icon_size", 42).coerceIn(28, 64)), dp(prefs.getInt("desktop_icon_size", 42).coerceIn(28, 64))))
        if (prefs.getBoolean("desktop_icon_labels", true)) cell.addView(label(info.loadLabel(packageManager).toString(), 9, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, dp(32)))
        grid.addView(cell, GridLayout.LayoutParams().apply {
            width = 0; height = dp(94); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun addLinuxDesktopIcon(grid: GridLayout, app: LinuxDesktopApp, key: String) {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(7))
            background = rounded(Color.argb(55, 42, 67, 77), 12)
            setOnClickListener { launchLinuxApp(app) }
            setOnLongClickListener { confirmRemoveShortcut(key, app.name); true }
        }
        cell.addView(label("🐧", prefs.getInt("desktop_icon_size", 42).coerceIn(28, 64), Color.WHITE, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, dp(prefs.getInt("desktop_icon_size", 42).coerceIn(28, 64))))
        if (prefs.getBoolean("desktop_icon_labels", true)) cell.addView(label(app.name, 9, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, dp(32)))
        grid.addView(cell, GridLayout.LayoutParams().apply {
            width = 0; height = dp(94); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun refreshLinuxApps() {
        if (linuxScanRunning) return
        linuxScanRunning = true
        Thread {
            val roots = distroIds.joinToString(" ") { "/data/local/mt-os/distros/$it" }
            val script = "for R in $roots; do [ -x \"\$R/bin/sh\" ] || continue; D=\$(basename \"\$R\"); " +
                "for P in \"\$R/usr/share/applications\" \"\$R/usr/local/share/applications\" \"\$R/root/.local/share/applications\"; do " +
                "[ -d \"\$P\" ] || continue; find \"\$P\" -type f -name '*.desktop' 2>/dev/null; done | sort -u | " +
                "while IFS= read -r F; do " +
                "N=\$(awk -F= '/^Name=/{print substr(\$0,index(\$0,\"=\")+1);exit}' \"\$F\"); " +
                "E=\$(awk -F= '/^Exec=/{print substr(\$0,index(\$0,\"=\")+1);exit}' \"\$F\"); " +
                "C=\$(awk -F= '/^Categories=/{print substr(\$0,index(\$0,\"=\")+1);exit}' \"\$F\"); " +
                "H=\$(awk -F= '/^(NoDisplay|Hidden)=/{if(\$2==\"true\") print \"yes\"}' \"\$F\" | head -n1); " +
                "[ -n \"\$N\" ] && [ -n \"\$E\" ] && [ -z \"\$H\" ] || continue; " +
                "B=\$(basename \"\$F\" .desktop); printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \"\$D\" \"\$N\" \"\$B\" \"\$E\" \"\$C\"; done; done"
            val result = RootWindowBridge(this).runRoot(script)
            val parsed: List<LinuxDesktopApp> = result.lineSequence().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size >= 4 && p[1].isNotBlank() && p[2].isNotBlank()) LinuxDesktopApp(p[0], p[1], p[2], p[3], p.getOrElse(4) { "" }) else null
            }.distinctBy { it.distro + ":" + it.desktopId }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
                .toList()
            runOnUiThread {
                linuxApps = parsed
                linuxScanRunning = false
                if (!isFinishing && !isDestroyed) buildDesktop()
            }
        }.apply { isDaemon = true; name = "MT-GNOME-LinuxAppScan"; start() }
    }

    private fun launchLinuxApp(app: LinuxDesktopApp) {
        Thread {
            val result = LinuxDistroLauncher.launchDesktopEntry(app.distro, app.desktopId)
            runOnUiThread {
                if (result.first == 0) {
                    Toast.makeText(this,
                        "Launching ${app.name}. A compatible running X11 or Wayland session is required.",
                        Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Could not launch ${app.name}")
                        .setMessage(result.second.take(1600))
                        .setPositiveButton("OK", null).show()
                }
            }
        }.apply { isDaemon = true; name = "MT-GNOME-LinuxAppLaunch"; start() }
    }

    private fun confirmRemoveShortcut(key: String, title: String) {
        AlertDialog.Builder(this).setTitle("Remove desktop shortcut?")
            .setMessage("\"$title\" will be removed from this desktop only. The installed app is not uninstalled.")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                val hidden = (prefs.getStringSet("hidden_desktop_shortcuts", emptySet()) ?: emptySet()).toMutableSet()
                hidden.add(key); prefs.edit().putStringSet("hidden_desktop_shortcuts", hidden).apply(); buildDesktop()
            }.show()
    }

    private fun showDesktopMenu(anchor: View) {
        val items = arrayOf("Applications", "Terminal", "Files", "Desktop settings", "Refresh Linux apps", "Workspaces", "System settings")
        AlertDialog.Builder(this).setTitle("MT-GNOME").setItems(items) { _, which ->
            when (which) {
                0 -> showApplicationDrawer()
                1 -> openTerminalWindow()
                2 -> openInternalWindow(FileManagerActivity::class.java)
                3 -> showDesktopCustomizer()
                4 -> refreshLinuxApps()
                5 -> showWorkspaceSwitcher()
                6 -> startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }.show()
    }

    private data class AppMenuEntry(
        val name: String, val category: String, val source: String,
        val key: String, val androidApp: ResolveInfo? = null, val linuxApp: LinuxDesktopApp? = null
    )

    private fun androidAppCategory(app: ResolveInfo): String {
        val text = (app.loadLabel(packageManager).toString() + " " + app.activityInfo.packageName).lowercase(Locale.ROOT)
        return when {
            listOf("browser", "chrome", "firefox", "internet", "mail", "email", "messag", "telegram", "whatsapp", "signal").any { text.contains(it) } -> "Internet"
            listOf("camera", "gallery", "photo", "video", "music", "audio", "youtube", "media").any { text.contains(it) } -> "Multimedia"
            listOf("word", "office", "document", "sheet", "pdf", "reader", "notes").any { text.contains(it) } -> "Office"
            listOf("settings", "file", "manager", "terminal", "backup", "security", "system").any { text.contains(it) } -> "System"
            listOf("developer", "ide", "code", "editor", "git", "debug").any { text.contains(it) } -> "Development"
            listOf("game", "play").any { text.contains(it) } -> "Games"
            listOf("calculator", "calendar", "clock", "contacts", "weather", "download", "maps").any { text.contains(it) } -> "Utilities"
            else -> "Other"
        }
    }

    private fun linuxAppCategory(app: LinuxDesktopApp): String {
        val categories = app.categories.lowercase(Locale.ROOT)
        val identity = (app.name + " " + app.desktopId).lowercase(Locale.ROOT)
        return when {
            listOf("information-gathering", "information_gathering", "reconnaissance").any { categories.contains(it) || identity.contains(it) } -> "Information Gathering"
            listOf("vulnerability-analysis", "vulnerability_analysis").any { categories.contains(it) || identity.contains(it) } -> "Vulnerability Analysis"
            listOf("web-application-analysis", "web_application_analysis").any { categories.contains(it) || identity.contains(it) } -> "Web Application Analysis"
            listOf("database-assessment", "database_assessment").any { categories.contains(it) || identity.contains(it) } -> "Database Assessment"
            listOf("password-attacks", "password_attacks", "password").any { categories.contains(it) || identity.contains(it) } -> "Password Attacks"
            listOf("wireless-attacks", "wireless_attacks", "wireless").any { categories.contains(it) || identity.contains(it) } -> "Wireless Attacks"
            listOf("reverse-engineering", "reverse_engineering").any { categories.contains(it) || identity.contains(it) } -> "Reverse Engineering"
            listOf("exploitation-tools", "exploitation_tools", "exploit").any { categories.contains(it) || identity.contains(it) } -> "Exploitation Tools"
            listOf("sniffing-spoofing", "sniffing_spoofing", "sniffer").any { categories.contains(it) || identity.contains(it) } -> "Sniffing & Spoofing"
            listOf("post-exploitation", "post_exploitation").any { categories.contains(it) || identity.contains(it) } -> "Post Exploitation"
            listOf("forensics", "forensic").any { categories.contains(it) || identity.contains(it) } -> "Forensics"
            listOf("reporting", "report").any { categories.contains(it) || identity.contains(it) } -> "Reporting"
            listOf("social-engineering", "social_engineering").any { categories.contains(it) || identity.contains(it) } -> "Social Engineering"
            listOf("system-services", "system_services").any { categories.contains(it) || identity.contains(it) } -> "System Services"
            categories.contains("security") || categories.contains("pentest") -> "Security Tools"
            categories.contains("development") || categories.contains("ide") -> "Development"
            categories.contains("network") || categories.contains("webbrowser") -> "Internet & Networking"
            categories.contains("audio") || categories.contains("video") || categories.contains("graphics") -> "Multimedia"
            categories.contains("office") || categories.contains("publishing") -> "Office"
            categories.contains("system") || categories.contains("settings") -> "System"
            categories.contains("utility") || categories.contains("accessibility") -> "Utilities"
            categories.contains("game") -> "Games"
            categories.contains("education") -> "Education"
            else -> "Other Linux Applications"
        }
    }
    private fun applicationMenuEntries(): List<AppMenuEntry> {
        val result = mutableListOf<AppMenuEntry>()
        if (prefs.getBoolean("show_android_apps", true)) apps.forEach { app ->
            val key = "android:${app.activityInfo.packageName}/${app.activityInfo.name}"
            result.add(AppMenuEntry(app.loadLabel(packageManager).toString(), androidAppCategory(app), "Android", key, androidApp = app))
        }
        if (prefs.getBoolean("show_linux_apps", true)) linuxApps.forEach { app ->
            val key = "linux:${app.distro}:${app.desktopId}"
            result.add(AppMenuEntry(app.name, linuxAppCategory(app), "Linux · ${app.distro}", key, linuxApp = app))
        }
        return result.sortedWith(compareBy<AppMenuEntry> { it.category }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    private fun addShortcut(entry: AppMenuEntry) {
        val shortcuts = (prefs.getStringSet("desktop_shortcuts", emptySet()) ?: emptySet()).toMutableSet()
        shortcuts.add(entry.key)
        val hidden = (prefs.getStringSet("hidden_desktop_shortcuts", emptySet()) ?: emptySet()).toMutableSet()
        hidden.remove(entry.key)
        prefs.edit().putStringSet("desktop_shortcuts", shortcuts).putStringSet("hidden_desktop_shortcuts", hidden).apply()
        buildDesktop()
        toast("${entry.name} added to desktop")
    }

    private fun showApplicationDrawer() {
        val entries = applicationMenuEntries()
        if (entries.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Applications")
                .setMessage(if (linuxScanRunning) "Scanning installed Linux applications…" else
                    "No applications are currently visible. Turn Android apps or Linux apps on in Settings → Applications.")
                .setPositiveButton("Settings") { _, _ -> showAppearance() }
                .setNegativeButton("Close", null).show()
            return
        }
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(25, 29, 39), 18)
        }
        root.addView(label("Applications", 20, Color.WHITE, true).apply { setPadding(dp(4), dp(4), dp(4), dp(8)) })
        val searchBox = EditText(this).apply {
            hint = "Search applications…"
            isSingleLine = true
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            background = rounded(Color.rgb(43, 48, 61), 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(searchBox, LinearLayout.LayoutParams(-1, dp(46)))
        val categories = (listOf("All") + entries.map { it.category }.distinct().sorted()).toMutableList()
        var selectedCategory = "All"
        val categoryBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryBar)
        }
        root.addView(categoryScroll, LinearLayout.LayoutParams(-1, dp(48)))
        val appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val appScroll = ScrollView(this).apply { addView(appList); isFillViewport = true }
        root.addView(appScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun renderApps() {
            categoryBar.removeAllViews()
            categories.forEach { category ->
                val chip = Button(this).apply {
                    text = category
                    isAllCaps = false
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    background = rounded(if (selectedCategory == category) themeColors()[1] else Color.rgb(45, 51, 66), 9)
                    setOnClickListener { selectedCategory = category; renderApps() }
                }
                categoryBar.addView(chip, LinearLayout.LayoutParams(-2, dp(38)).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) })
            }
            appList.removeAllViews()
            val term = searchBox.text.toString().trim().lowercase(Locale.ROOT)
            val filtered = entries.filter { (selectedCategory == "All" || it.category == selectedCategory) &&
                (term.isEmpty() || it.name.lowercase(Locale.ROOT).contains(term) || it.category.lowercase(Locale.ROOT).contains(term)) }
            if (filtered.isEmpty()) appList.addView(label("No matching applications", 12, muted).apply { setPadding(dp(12), dp(18), dp(12), dp(18)) })
            filtered.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(Color.rgb(38, 44, 57), 10)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                }
                if (entry.androidApp != null) {
                    val icon = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        try { setImageDrawable(entry.androidApp.loadIcon(packageManager)) } catch (_: Exception) {}
                        contentDescription = entry.name
                    }
                    row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
                } else {
                    row.addView(label("🐧", 24, Color.WHITE, true).apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(dp(42), dp(42)))
                }
                val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, dp(4), 0) }
                textColumn.addView(label(entry.name, 12, Color.WHITE, true))
                textColumn.addView(label("${entry.category}  ·  ${entry.source}", 9, muted))
                row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
                row.setOnClickListener {
                    dialog.dismiss()
                    entry.androidApp?.let { launchApp(it) }
                    entry.linuxApp?.let { launchLinuxApp(it) }
                }
                row.setOnLongClickListener {
                    AlertDialog.Builder(this).setTitle("Add desktop shortcut?")
                        .setMessage("Add ${entry.name} to the desktop?").setNegativeButton("Cancel", null)
                        .setPositiveButton("Add") { _, _ -> addShortcut(entry) }.show()
                    true
                }
                val add = Button(this).apply {
                    text = "＋"
                    contentDescription = "Add ${entry.name} to desktop"
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    background = rounded(themeColors()[1], 8)
                    setOnClickListener { addShortcut(entry) }
                }
                row.addView(add, LinearLayout.LayoutParams(dp(42), dp(40)))
                appList.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(5) })
            }
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderApps() }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderApps()
        root.addView(Button(this).apply {
            text = "Close"; isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(42)))
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .94f).toInt(),
            (resources.displayMetrics.heightPixels * .86f).toInt())
    }

    private fun showDesktopCustomizer() {
        val options = arrayOf("Show desktop icon labels", "Show Android apps", "Show Linux apps",
            "Show clock in top panel", "Show workspace button", "Show settings button",
            "Show Terminal in dock", "Show Files in dock", "Show Tools in dock", "Show Workspaces in dock", "Show Customize in dock")
        val keys = arrayOf("desktop_icon_labels", "show_android_apps", "show_linux_apps",
            "show_top_clock", "show_workspace_button", "show_top_settings",
            "dock_terminal", "dock_files", "dock_tools", "dock_workspaces", "dock_customize")
        val defaults = BooleanArray(options.size) { true }
        val checked = BooleanArray(options.size) { i -> prefs.getBoolean(keys[i], defaults[i]) }
        AlertDialog.Builder(this).setTitle("Customize desktop")
            .setMultiChoiceItems(options, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNeutralButton("More options") { _, _ -> showDesktopAdvancedOptions() }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val edit = prefs.edit()
                keys.forEachIndexed { i, key -> edit.putBoolean(key, checked[i]) }
                edit.apply()
                buildDesktop()
            }.show()
    }

    private fun showDesktopAdvancedOptions() {
        val options = arrayOf("Change desktop icon size", "Rename desktop", "Change wallpaper",
            "Restore removed shortcuts", "Reset desktop layout")
        AlertDialog.Builder(this).setTitle("Advanced desktop options").setItems(options) { _, which ->
            when (which) {
                0 -> {
                    val sizes = arrayOf("Small", "Medium", "Large", "Extra large")
                    val values = intArrayOf(32, 42, 52, 62)
                    val current = prefs.getInt("desktop_icon_size", 42)
                    val selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 1
                    AlertDialog.Builder(this).setTitle("Desktop icon size").setSingleChoiceItems(sizes, selected) { dialog, index ->
                        prefs.edit().putInt("desktop_icon_size", values[index]).apply()
                        dialog.dismiss(); buildDesktop()
                    }.setNegativeButton("Cancel", null).show()
                }
                1 -> {
                    val input = EditText(this).apply {
                        setSingleLine(true)
                        setText(prefs.getString("desktop_profile_name", "Desktop"))
                        hint = "Desktop name"
                    }
                    AlertDialog.Builder(this).setTitle("Rename desktop").setView(input)
                        .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                            val name = input.text.toString().trim().take(28)
                            if (name.isNotEmpty()) prefs.edit().putString("desktop_profile_name", name).apply()
                            buildDesktop()
                        }.show()
                }
                2 -> chooseWorkspaceWallpaper()
                3 -> {
                    prefs.edit().remove("hidden_desktop_shortcuts").apply()
                    buildDesktop()
                }
                4 -> {
                    val edit = prefs.edit().remove("hidden_desktop_shortcuts")
                    listOf("dock_terminal", "dock_files", "dock_tools", "dock_workspaces", "dock_customize",
                        "show_android_apps", "show_linux_apps", "desktop_icon_labels",
                        "show_top_clock", "show_workspace_button", "show_top_settings").forEach { edit.putBoolean(it, true) }
                    edit.putInt("desktop_icon_size", 42).apply()
                    buildDesktop()
                }
            }
        }.show()
    }

    /** Four persistent desktop spaces; Android continues to own launched app tasks. */
    private fun showWorkspaceSwitcher() {
        val names = (1..4).map { index ->
            val name = prefs.getString("workspace_${index}_name", "Workspace $index") ?: "Workspace $index"
            if (index == activeWorkspace) "✓  $name  ·  Active" else name
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Switch desktop workspace").setItems(names) { _, which ->
            activeWorkspace = which + 1
            wallpaperName = prefs.getString("workspace_${activeWorkspace}_wallpaper", "Aurora") ?: "Aurora"
            prefs.edit().putInt("active_workspace", activeWorkspace).apply()
            buildDesktop()
            Toast.makeText(this, "Workspace " + activeWorkspace + " selected", Toast.LENGTH_SHORT).show()
        }.setNeutralButton("Rename") { _, _ -> renameWorkspace() }
            .setPositiveButton("Wallpaper") { _, _ -> chooseWorkspaceWallpaper() }.show()
    }

    private fun renameWorkspace() {
        val choices = (1..4).map { "Workspace " + it }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Rename which workspace?").setItems(choices) { _, which ->
            val index = which + 1
            val input = EditText(this).apply { setText(prefs.getString("workspace_${index}_name", "Workspace $index")) }
            AlertDialog.Builder(this).setTitle("Workspace name").setView(input)
                .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                    val name = input.text.toString().trim().take(32)
                    if (name.isNotEmpty()) prefs.edit().putString("workspace_${index}_name", name).apply()
                }.show()
        }.show()
    }

    private fun chooseWorkspaceWallpaper() {
        showWallpaperPicker()
    }
    private fun showWallpaperPicker(onSelected: (() -> Unit)? = null) {
        val choices = listOf(
            "Mountain Lake" to "mountain_lake.jpg",
            "Forest" to "forest.jpg",
            "Mountain Peaks" to "mountain_peaks.jpg",
            "Tropical Beach" to "tropical_beach.jpg",
            "Open Landscape" to "open_landscape.jpg"
        )
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(24, 28, 38), 18)
        }
        root.addView(label("Choose a wallpaper", 19, Color.WHITE, true).apply {
            setPadding(dp(4), dp(4), dp(4), dp(10))
        })
        root.addView(label("Tap an image to apply it to your desktop.", 10, muted).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        choices.forEach { (name, asset) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.rgb(40, 46, 60), 12)
                setPadding(dp(7), dp(7), dp(7), dp(7))
            }
            val preview = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "$name wallpaper preview"
                try {
                    val options = BitmapFactory.Options().apply { inSampleSize = 3 }
                    assets.open("wallpapers/$asset").use { stream ->
                        setImageBitmap(BitmapFactory.decodeStream(stream, null, options))
                    }
                } catch (_: Exception) { setBackgroundColor(Color.DKGRAY) }
            }
            card.addView(preview, LinearLayout.LayoutParams(-1, dp(118)))
            card.addView(label(if (wallpaperName == name) "✓  $name  ·  Current" else name,
                12, Color.WHITE, true).apply { setPadding(dp(5), dp(7), dp(5), dp(4)) })
            card.setOnClickListener {
                wallpaperName = name
                prefs.edit().putString("wallpaper", name)
                    .putString("workspace_${activeWorkspace}_wallpaper", name)
                    .remove("wallpaper_uri").apply()
                buildDesktop()
                dialog.dismiss()
                onSelected?.invoke()
                toast("$name wallpaper applied")
            }
            list.addView(card, LinearLayout.LayoutParams(-1, dp(158)).apply { bottomMargin = dp(9) })
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "Choose image from phone"; isAllCaps = false
            setOnClickListener {
                dialog.dismiss()
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(pick, wallpaperRequest)
            }
        }, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(Button(this).apply { text = "Close"; isAllCaps = false; setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(-1, dp(40)))
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(),
            (resources.displayMetrics.heightPixels * .86f).toInt())
    }

    @Suppress("DEPRECATION")
    private fun loadApps() {
        val flags = android.content.pm.PackageManager.MATCH_ALL
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val discovered = packageManager.queryIntentActivities(query, flags).toMutableList()
        val represented = discovered.mapNotNull { it.activityInfo?.packageName }.toMutableSet()

        // Samsung and other OEM apps are not always returned by the launcher-intent query.
        // Enumerate every installed package as well, then create a display entry when its
        // launcher activity is hidden or absent. Such entries remain visible in the drawer.
        val installed = try { packageManager.getInstalledApplications(flags) } catch (_: Exception) { emptyList() }
        installed.forEach { appInfo ->
            val pkg = appInfo.packageName ?: return@forEach
            if (pkg == packageName || represented.contains(pkg)) return@forEach
            val launchIntent = try { packageManager.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
            val resolved = launchIntent?.let {
                try { packageManager.resolveActivity(it, flags) } catch (_: Exception) { null }
            }
            val entry = resolved?.takeIf { it.activityInfo != null } ?: ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    applicationInfo = appInfo
                    packageName = pkg
                    name = ""
                    enabled = appInfo.enabled
                }
            }
            discovered.add(entry)
            represented.add(pkg)
        }
        apps = discovered
            .filter { it.activityInfo?.packageName != packageName }
            .distinctBy { it.activityInfo?.packageName + "/" + it.activityInfo?.name }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
        buildDesktop()
    }

    private fun renderApps() {
        // Rebuild the unified desktop grid so a refresh never drops Linux shortcuts.
        if (::appGrid.isInitialized) buildDesktop()
    }

    private fun launchApp(info: ResolveInfo) {
        val pkg = info.activityInfo?.packageName ?: return
        val launchIntent = try { packageManager.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
        if (launchIntent == null) {
            toast("This installed package has no launchable activity. Opening its app details.")
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) }
            catch (_: Exception) { toast("Android does not expose a launch activity for $pkg") }
            return
        }
        val component = launchIntent.component?.flattenToString()
            ?: packageManager.resolveActivity(launchIntent, android.content.pm.PackageManager.MATCH_ALL)
                ?.activityInfo?.let { "${it.packageName}/${it.name}" }
        if (component.isNullOrBlank()) {
            try { startActivity(launchIntent) } catch (_: Exception) { toast("Unable to launch $pkg") }
            return
        }
        val orientation = prefs.getString("orientation", "landscape") ?: "landscape"
        RootWindowBridge(this).launchFreeformComponent(component, orientation) {
            try { startActivity(launchIntent) } catch (_: Exception) { toast("Unable to launch $pkg") }
        }
    }

    private fun openInternalWindow(activityClass: Class<out Activity>) {
        val intent = Intent(this, activityClass)
        val component = "$packageName/${activityClass.name}"
        val orientation = prefs.getString("orientation", "landscape") ?: "landscape"
        RootWindowBridge(this).launchFreeformComponent(component, orientation) {
            try { startActivity(intent) } catch (_: Exception) { toast("Could not open ${activityClass.simpleName}") }
        }
    }

    private fun openTerminalWindow() {
        openInternalWindow(DistroCatalogActivity::class.java)
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
        val heading = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; background=rounded(Color.rgb(35,39,52),12); setPadding(dp(10),dp(5),dp(8),dp(5)) }
        val titleText = label("MT-GNOME Settings",17,Color.WHITE,true)
        heading.addView(titleText,LinearLayout.LayoutParams(0,dp(40),1f))
        var minimized = false
        var maximized = false
        var savedWidth = (resources.displayMetrics.widthPixels*.94f).toInt()
        var savedHeight = (resources.displayMetrics.heightPixels*.88f).toInt()
        var dragStartX=0f; var dragStartY=0f; var dragWindowX=0; var dragWindowY=0
        val minimizeButton = label("—",19,Color.WHITE,true)
        val maximizeButton = label("□",18,Color.WHITE,true)
        val closeButton = label("✕",18,Color.WHITE,true)
        listOf(minimizeButton,maximizeButton,closeButton).forEach { it.gravity=Gravity.CENTER; it.setPadding(dp(9),dp(5),dp(9),dp(5)); heading.addView(it) }
        closeButton.setOnClickListener { dialog.dismiss() }
        lateinit var body: LinearLayout
        lateinit var footer: Button
        val restoreSettings = {
            if (minimized) {
                minimized = false
                body.visibility = View.VISIBLE
                footer.visibility = View.VISIBLE
                root.getChildAt(1).visibility = View.VISIBLE
                dialog.window?.setLayout(savedWidth,savedHeight)
                titleText.text = "MT-GNOME Settings"
            }
        }
        minimizeButton.setOnClickListener {
            if (!minimized) {
                savedWidth = dialog.window?.attributes?.width?.takeIf { it > 0 } ?: savedWidth
                savedHeight = dialog.window?.attributes?.height?.takeIf { it > 0 } ?: savedHeight
                minimized = true
                body.visibility = View.GONE
                footer.visibility = View.GONE
                root.getChildAt(1).visibility = View.GONE
                dialog.window?.setLayout(dp(260),dp(58))
                titleText.text = "MT-GNOME Settings  ·  minimized"
            } else restoreSettings()
        }
        maximizeButton.setOnClickListener {
            restoreSettings()
            if (!maximized) {
                savedWidth = dialog.window?.attributes?.width?.takeIf { it > 0 } ?: savedWidth
                savedHeight = dialog.window?.attributes?.height?.takeIf { it > 0 } ?: savedHeight
                dialog.window?.setLayout(-1,-1)
                maximized = true
                maximizeButton.text = "❐"
            } else {
                dialog.window?.setLayout(savedWidth,savedHeight)
                maximized = false
                maximizeButton.text = "□"
            }
        }
        heading.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { dragStartX=event.rawX; dragStartY=event.rawY; dragWindowX=dialog.window?.attributes?.x ?: 0; dragWindowY=dialog.window?.attributes?.y ?: 0; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val w=dialog.window ?: return@setOnTouchListener true
                    val a=w.attributes
                    a.gravity=Gravity.TOP or Gravity.START
                    a.x=(dragWindowX+event.rawX-dragStartX).toInt().coerceAtLeast(0)
                    a.y=(dragWindowY+event.rawY-dragStartY).toInt().coerceAtLeast(0)
                    w.attributes=a
                    true
                }
                else -> true
            }
        }
        titleText.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { dragStartX=event.rawX; dragStartY=event.rawY; dragWindowX=dialog.window?.attributes?.x ?: 0; dragWindowY=dialog.window?.attributes?.y ?: 0; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val w=dialog.window ?: return@setOnTouchListener true
                    val a=w.attributes; a.gravity=Gravity.TOP or Gravity.START
                    a.x=(dragWindowX+event.rawX-dragStartX).toInt().coerceAtLeast(0)
                    a.y=(dragWindowY+event.rawY-dragStartY).toInt().coerceAtLeast(0)
                    w.attributes=a; true
                }
                android.view.MotionEvent.ACTION_UP -> { if (minimized) restoreSettings(); true }
                else -> true
            }
        }
        root.addView(heading)
        root.addView(label("Desktop configuration",10,muted).apply { setPadding(0,dp(4),0,dp(10)) })
        body=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val nav=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=rounded(Color.rgb(36,38,49),12); setPadding(dp(4),dp(4),dp(4),dp(4)) }
        val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        val scroll=ScrollView(this).apply { isFillViewport=true }; scroll.addView(panel)
        val navScroll = ScrollView(this).apply { isFillViewport = true; addView(nav) }
        body.addView(navScroll,LinearLayout.LayoutParams(dp(108),-1)); body.addView(scroll,LinearLayout.LayoutParams(0,-1,1f))
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
            page("Appearance","Themes, wallpaper, accent colours and translucent surfaces")
            item("Custom accent colour", "Current: ${prefs.getString("custom_accent", "Theme default") ?: "Theme default"} · changes highlighted desktop controls") {
                val names = arrayOf("Theme default", "Sky blue", "Violet", "Mint", "Coral", "Amber", "Pink", "White")
                val values = arrayOf("", "#7DAAFF", "#B69CFF", "#66D9B5", "#FF8A75", "#FFBF5F", "#F08CCB", "#FFFFFF")
                val current = prefs.getString("custom_accent", "") ?: ""
                val selected = values.indexOf(current).coerceAtLeast(0)
                AlertDialog.Builder(this).setTitle("Choose accent colour")
                    .setSingleChoiceItems(names, selected) { d, i ->
                        prefs.edit().putString("custom_accent", values[i]).apply()
                        window.statusBarColor = themeColors()[0]
                        window.navigationBarColor = themeColors()[0]
                        buildDesktop(); d.dismiss(); toast("Accent colour updated")
                    }.setNegativeButton("Cancel", null).show()
            }
            item("Top panel transparency", "Current: ${prefs.getInt("panel_opacity", 82)}% · lower values reveal more wallpaper") {
                val values = intArrayOf(20, 30, 40, 50, 60, 70, 82, 90, 100)
                AlertDialog.Builder(this).setTitle("Top panel opacity")
                    .setSingleChoiceItems(values.map { "$it%" }.toTypedArray(), values.indexOf(prefs.getInt("panel_opacity", 82)).coerceAtLeast(0)) { d, i ->
                        prefs.edit().putInt("panel_opacity", values[i]).apply(); buildDesktop(); d.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
            item("Dock transparency", "Current: ${prefs.getInt("dock_surface_opacity", 88)}% · independent from the top panel") {
                val values = intArrayOf(20, 30, 40, 50, 60, 70, 80, 88, 100)
                AlertDialog.Builder(this).setTitle("Dock opacity")
                    .setSingleChoiceItems(values.map { "$it%" }.toTypedArray(), values.indexOf(prefs.getInt("dock_surface_opacity", 88)).coerceAtLeast(0)) { d, i ->
                        prefs.edit().putInt("dock_surface_opacity", values[i]).apply(); buildDesktop(); d.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
            item("Desktop theme", "Current: ${prefs.getString("desktop_theme", "Midnight") ?: "Midnight"} · 12 built-in colour styles") {
                showThemePicker { dialog.dismiss() }
            }
            item("Choose built-in wallpaper", "Browse large image previews before applying a wallpaper") {
                showWallpaperPicker { dialog.dismiss() }
            }
            item("Choose from phone storage","Select an image from internal storage or SD card") {
                val pick=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
                startActivityForResult(pick,wallpaperRequest)
            }
        }
        navItem("Input & Pointer") {
            page("Input & Pointer","Professional mouse pointer controls across the entire screen")
            item("Direct touch","Use the phone screen directly; hide the virtual pointer and trackpad") {
                prefs.edit().putString("input_mode","direct").apply()
                stopService(Intent(this, PointerOverlayService::class.java))
                toast("Direct touch enabled")
            }
            item("Full-screen mouse mode","Touch anywhere to move the desktop arrow pointer. Tap to click, double-tap to double-click, hold for right-click and use two fingers to scroll.") {
                prefs.edit().putString("input_mode","trackpad").apply()
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
                } else {
                    startService(Intent(this, PointerOverlayService::class.java))
                    if (!MTGNOMEAccessibilityService.isConnected()) {
                        AlertDialog.Builder(this).setTitle("Enable MT-GNOME Pointer")
                            .setMessage("Android must enable MT-GNOME Pointer in Accessibility settings before pointer clicks and gestures can be sent to other apps.")
                            .setPositiveButton("Open Accessibility settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            .setNegativeButton("Later", null).show()
                    } else toast("Full-screen mouse enabled — touch anywhere to control the pointer")
                }
            }
            item("Overlay permission","Allow the dock or pointer to appear above other apps") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
            item("Enable MT-GNOME Pointer","Turn on MT-GNOME Pointer under Installed services in Android Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        navItem("App Bar") {
            page("App Bar","Control the desktop dock and its floating toggle")
            item("Show app bar","Display the dock above the desktop and other apps") {
                prefs.edit().putBoolean("dock_visible", true).apply()
                if (Settings.canDrawOverlays(this)) startService(Intent(this, DesktopDockService::class.java).setAction("com.mt.gnome.DOCK_SHOW"))
                else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            item("Hide app bar","Hide the dock but keep the floating toggle button") {
                startService(Intent(this, DesktopDockService::class.java).setAction("com.mt.gnome.DOCK_HIDE"))
            }
            item("Toggle app bar","Switch between visible and hidden dock states") {
                if (Settings.canDrawOverlays(this)) startService(Intent(this, DesktopDockService::class.java).setAction("com.mt.gnome.DOCK_TOGGLE"))
                else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            item("Show floating app-bar button","Keep the small show/hide control above other apps") {
                if (Settings.canDrawOverlays(this)) startService(Intent(this, DesktopDockService::class.java).setAction("com.mt.gnome.DOCK_TOGGLE_BUTTON_SHOW"))
                else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            item("Hide floating app-bar button","Remove the small control; the app bar can still be restored from Settings") {
                startService(Intent(this, DesktopDockService::class.java).setAction("com.mt.gnome.DOCK_TOGGLE_BUTTON_HIDE"))
            }
            item("Overlay permission","Allow the small dock toggle to remain above other apps") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            item("App bar width","Current: ${prefs.getInt("dock_width",66)} dp") {
                val widths=intArrayOf(52,60,66,76,88,100)
                AlertDialog.Builder(this).setTitle("App bar width").setSingleChoiceItems(widths.map { "$it dp" }.toTypedArray(), widths.indexOf(prefs.getInt("dock_width",66)).coerceAtLeast(0)) { d,i ->
                    prefs.edit().putInt("dock_width",widths[i]).apply()
                    d.dismiss()
                    if (Settings.canDrawOverlays(this)) {
                        stopService(Intent(this, DesktopDockService::class.java))
                        startService(Intent(this, DesktopDockService::class.java))
                    }
                    toast("App bar width saved")
                }.setNegativeButton("Cancel",null).show()
            }
            item("App bar opacity","Current: ${prefs.getInt("dock_opacity",96)}%") {
                val values=intArrayOf(35,50,65,75,85,96,100)
                AlertDialog.Builder(this).setTitle("App bar opacity").setSingleChoiceItems(values.map { "$it%" }.toTypedArray(), values.indexOf(prefs.getInt("dock_opacity",96)).coerceAtLeast(0)) { d,i ->
                    prefs.edit().putInt("dock_opacity",values[i]).apply()
                    d.dismiss()
                    if (Settings.canDrawOverlays(this)) {
                        stopService(Intent(this, DesktopDockService::class.java))
                        startService(Intent(this, DesktopDockService::class.java))
                    }
                    toast("App bar opacity saved")
                }.setNegativeButton("Cancel",null).show()
            }
            item("Desktop utilities","Open system monitor, file access, workspaces and quick settings") {
                openInternalWindow(DesktopToolsActivity::class.java)
            }
        }
        navItem("Windows") {
            page("Windows","Floating app window behaviour")
            item("Enable freeform windows","Uses Magisk root; firmware may restrict some apps") { dialog.dismiss(); enableLinuxWindows() }
            item("Display over other apps","Manage Android overlay access") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
        }
        navItem("Terminal") {
            page("Terminal","Terminal appearance and behaviour")
            item("Default distribution",prefs.getString("active_distro","Select when opening") ?: "Select when opening") { toast("Choose the active distribution in DISTROS") }
        }
        navItem("Display") {
            page("Display","Screen configuration, desktop density and layout")
            item("Desktop grid columns", "Current: ${prefs.getInt("desktop_columns", if (resources.configuration.screenWidthDp >= 700) 6 else 4)} columns · choose how tightly app shortcuts are arranged") {
                val values = intArrayOf(3, 4, 5, 6, 7, 8)
                val current = prefs.getInt("desktop_columns", if (resources.configuration.screenWidthDp >= 700) 6 else 4)
                AlertDialog.Builder(this).setTitle("Desktop grid density")
                    .setSingleChoiceItems(values.map { "$it columns" }.toTypedArray(), values.indexOf(current).coerceAtLeast(0)) { d, i ->
                        prefs.edit().putInt("desktop_columns", values[i]).apply(); buildDesktop(); d.dismiss()
                    }.setNegativeButton("Cancel", null).show()
            }
            item("Orientation",prefs.getString("orientation","landscape") ?: "landscape") { showOrientationSettings() }
            item("Desktop icon size","Current: ${prefs.getInt("desktop_icon_size",34)} dp · changes app icons on the desktop") {
                val sizes = intArrayOf(28, 34, 40, 48, 56)
                val current = sizes.indexOf(prefs.getInt("desktop_icon_size",34)).coerceAtLeast(0)
                AlertDialog.Builder(this).setTitle("Desktop icon size")
                    .setSingleChoiceItems(sizes.map { "$it dp" }.toTypedArray(), current) { d, i ->
                        prefs.edit().putInt("desktop_icon_size", sizes[i]).apply()
                        d.dismiss(); buildDesktop(); toast("Desktop icon size updated")
                    }.setNegativeButton("Cancel", null).show()
            }
            item("Desktop icon labels",if (prefs.getBoolean("desktop_icon_labels",true)) "Labels are visible" else "Labels are hidden") {
                val visible = !prefs.getBoolean("desktop_icon_labels",true)
                prefs.edit().putBoolean("desktop_icon_labels",visible).apply()
                buildDesktop(); toast(if (visible) "Desktop labels shown" else "Desktop labels hidden")
            }
            item("Logical resolution","Android display size; physical panel is unchanged") { showResolutionSettings() }
        }
        navItem("Applications") {
            fun renderVisibility() {
                page("Application visibility","Choose which app ecosystems appear on the desktop and in the Applications menu. Both are enabled by default.")
                item(if (prefs.getBoolean("show_android_apps", true)) "●  Android apps — SHOW" else "○  Android apps — HIDE",
                    "Show or hide installed Android app shortcuts. This does not uninstall or disable apps.") {
                    toggleAppVisibility("show_android_apps")
                    renderVisibility()
                }
                item(if (prefs.getBoolean("show_linux_apps", true)) "●  Linux apps — SHOW" else "○  Linux apps — HIDE",
                    "Show or hide installed Linux desktop applications. This does not uninstall Linux packages.") {
                    toggleAppVisibility("show_linux_apps")
                    renderVisibility()
                }
                item("Show both app types", "Restore the complete desktop app view") {
                    prefs.edit().putBoolean("show_android_apps", true).putBoolean("show_linux_apps", true).apply()
                    buildDesktop()
                    renderVisibility()
                    toast("Android and Linux apps shown")
                }
            }
            renderVisibility()
        }
        navItem("System & Security") {
            page("System & Security","Root access, Android permissions and optional command-line utilities")
            item("Root access","Authorize or verify Magisk integration") { enableLinuxWindows() }
            item("BusyBox prerequisite","Check whether the BusyBox binary provided by your Magisk module is available to MT-GNOME. BusyBox is needed for the supported root-assisted distribution utility workflow; it does not replace the private mount namespace or make unsafe commands safe.") { checkBusyBox() }
            item("Notification permission","Allow MT-GNOME to show installation and desktop notifications") {
                if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 8112)
                else toast("Notification permission is already granted")
            }
            item("Display over other apps","Grant special access for the floating app bar and pointer") { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            item("Accessibility service","Enable MT-GNOME Pointer under Installed services") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            item("Android app permissions","Review and grant available runtime permissions") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            item("System settings","Open Android settings") { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        navItem("About") { page("About MT-GNOME","MT-OS desktop shell"); item("Version","0.5.0-alpha") {}; item("Developer","Tinotenda Enock Mapfumo") {}; item("Device","${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}") {} }
        page("Appearance","Wallpaper and desktop presentation")
        footer = Button(this).apply { text="Close settings"; isAllCaps=false; setOnClickListener { dialog.dismiss() } }
        root.addView(footer,LinearLayout.LayoutParams(-1,dp(42)).apply { topMargin=dp(10) })
        val grip = label("◢",16,Color.LTGRAY,true).apply { gravity=Gravity.END; setPadding(dp(10),dp(4),dp(4),dp(2)) }
        root.addView(grip,LinearLayout.LayoutParams(-1,dp(24)))
        var resizeX=0f; var resizeY=0f; var resizeW=0; var resizeH=0
        grip.setOnTouchListener { _, event ->
            when(event.action) {
                android.view.MotionEvent.ACTION_DOWN -> { resizeX=event.rawX; resizeY=event.rawY; resizeW=dialog.window?.attributes?.width ?: savedWidth; resizeH=dialog.window?.attributes?.height ?: savedHeight; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val w=dialog.window ?: return@setOnTouchListener true
                    val maxW=resources.displayMetrics.widthPixels; val maxH=resources.displayMetrics.heightPixels
                    w.setLayout((resizeW+event.rawX-resizeX).toInt().coerceIn(dp(280),maxW),(resizeH+event.rawY-resizeY).toInt().coerceIn(dp(220),maxH))
                    true
                }
                else -> true
            }
        }
        dialog.setContentView(root); dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(savedWidth,savedHeight)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.attributes=dialog.window?.attributes?.apply { gravity=Gravity.CENTER; dimAmount=.42f }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
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

    // Lightweight offline theme palette: dock, controls and accent colours only.
    private fun themeColors(theme: String = prefs.getString("desktop_theme", "Midnight") ?: "Midnight"): IntArray {
        val colors = when (theme) {
        "Nord" -> intArrayOf(Color.rgb(46,52,64), Color.rgb(136,192,208), Color.rgb(59,66,82), Color.rgb(236,239,244))
        "Dracula" -> intArrayOf(Color.rgb(40,42,54), Color.rgb(189,147,249), Color.rgb(68,71,90), Color.rgb(248,248,242))
        "Solarized" -> intArrayOf(Color.rgb(0,43,54), Color.rgb(38,139,210), Color.rgb(7,54,66), Color.rgb(238,232,213))
        "Ocean" -> intArrayOf(Color.rgb(5,32,55), Color.rgb(54,190,220), Color.rgb(9,57,83), Color.rgb(225,245,255))
        "Forest" -> intArrayOf(Color.rgb(23,43,35), Color.rgb(132,194,139), Color.rgb(38,69,51), Color.rgb(235,247,235))
        "Sunset" -> intArrayOf(Color.rgb(54,30,42), Color.rgb(255,145,110), Color.rgb(83,42,55), Color.rgb(255,237,226))
        "Rose Pine" -> intArrayOf(Color.rgb(35,33,54), Color.rgb(235,188,186), Color.rgb(57,53,78), Color.rgb(246,237,237))
        "Cyberpunk" -> intArrayOf(Color.rgb(25,18,43), Color.rgb(0,245,212), Color.rgb(53,26,73), Color.rgb(255,226,250))
        "Amber" -> intArrayOf(Color.rgb(43,34,22), Color.rgb(255,190,75), Color.rgb(74,54,30), Color.rgb(255,244,220))
        "Paper" -> intArrayOf(Color.rgb(226,222,211), Color.rgb(44,83,100), Color.rgb(242,239,230), Color.rgb(35,42,48))
        "Light" -> intArrayOf(Color.rgb(235,239,246), Color.rgb(44,91,160), Color.rgb(250,251,255), Color.rgb(34,43,58))
        else -> intArrayOf(Color.rgb(20,22,29), Color.rgb(125,170,255), Color.rgb(45,49,64), Color.rgb(235,239,250))
        }
        val customAccent = prefs.getString("custom_accent", "")
        if (!customAccent.isNullOrBlank()) {
            try { colors[1] = Color.parseColor(customAccent) } catch (_: IllegalArgumentException) { }
        }
        return colors
    }

    private fun showThemePicker(onApplied: () -> Unit = {}) {
        val themes = arrayOf("Midnight", "Nord", "Dracula", "Solarized", "Ocean", "Forest", "Sunset", "Rose Pine", "Cyberpunk", "Amber", "Paper", "Light")
        val current = prefs.getString("desktop_theme", "Midnight") ?: "Midnight"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        fun applyTheme(name: String, dialog: AlertDialog) {
            prefs.edit().putString("desktop_theme", name).apply()
            window.statusBarColor = themeColors()[0]
            window.navigationBarColor = themeColors()[0]
            buildDesktop()
            renderApps()
            dialog.dismiss()
            onApplied()
            toast("$name theme applied")
        }
        lateinit var dialog: AlertDialog
        themes.forEach { name ->
            val colors = themeColors(name)
            val selected = name == current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(if (selected) colors[2] else Color.rgb(37, 40, 50), 12)
                isClickable = true
                isFocusable = true
            }
            val preview = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(colors[0], 8)
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            preview.addView(View(this).apply {
                setBackgroundColor(colors[3])
            }, LinearLayout.LayoutParams(-1, dp(5)))
            val previewBody = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            previewBody.addView(View(this).apply { setBackgroundColor(colors[1]) }, LinearLayout.LayoutParams(dp(13), dp(13)))
            previewBody.addView(View(this).apply { setBackgroundColor(colors[2]) }, LinearLayout.LayoutParams(0, dp(13), 1f).apply { leftMargin = dp(5) })
            preview.addView(previewBody, LinearLayout.LayoutParams(-1, dp(24)))
            preview.addView(View(this).apply { setBackgroundColor(colors[1]) }, LinearLayout.LayoutParams(-1, dp(4)))
            row.addView(preview, LinearLayout.LayoutParams(dp(88), dp(48)))
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            textColumn.addView(label(name, 13, Color.WHITE, true))
            textColumn.addView(label(if (selected) "Currently selected" else "Preview this colour palette", 10, muted))
            row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(if (selected) "✓" else "›", 19, colors[1], true))
            row.setOnClickListener { applyTheme(name, dialog) }
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Choose desktop theme")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
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
        val selected = prefs.getString("orientation", "landscape")
        requestedOrientation = when (selected) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "system" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        // Keep the device rotation aligned when an external app is launched in freeform.
        Thread {
            val rotation = when (selected) { "portrait" -> 0; "landscape" -> 1; else -> -1 }
            val command = if (rotation < 0) "settings put system accelerometer_rotation 1"
                else "settings put system accelerometer_rotation 0 && settings put system user_rotation $rotation"
            RootWindowBridge(this).runRoot(command)
        }.apply { isDaemon = true; name = "MT-GNOME-Orientation"; start() }
    }

    private fun toggleAppVisibility(key: String) {
        val enabled = !prefs.getBoolean(key, true)
        prefs.edit().putBoolean(key, enabled).apply()
        buildDesktop()
        toast(if (key == "show_android_apps") {
            if (enabled) "Android apps shown" else "Android apps hidden"
        } else {
            if (enabled) "Linux apps shown" else "Linux apps hidden"
        })
    }

    private fun checkBusyBox() {
        Thread {
            val dollar = 36.toChar()
            val command = "B=" + dollar + "(command -v busybox 2>/dev/null); " +
                "if [ -z \"" + dollar + "B\" ]; then for C in /system/xbin/busybox /system/bin/busybox /data/adb/modules/*/system/xbin/busybox /data/adb/modules/*/system/bin/busybox; do [ -x \"" + dollar + "C\" ] && B=\"" + dollar + "C\" && break; done; fi; " +
                "if [ -n \"" + dollar + "B\" ]; then echo \"Found: " + dollar + "B\"; " +
                "\"" + dollar + "B\" | head -n 1; \"" + dollar + "B\" true && echo 'BusyBox execution: OK'; " +
                "else echo 'BusyBox was not found in the root shell PATH.'; fi"
            val result = RootWindowBridge(this).runRoot(command)
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Magisk BusyBox check")
                    .setMessage(result.take(2400) + "\n\nBusyBox supplies common shell utilities. MT-GNOME keeps Linux app launches in a private mount namespace with a separate devpts instance; BusyBox is not a security boundary and does not remove the need for isolation.")
                    .setPositiveButton("OK", null).show()
            }
        }.apply { isDaemon = true; name = "MT-GNOME-BusyBoxCheck"; start() }
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
        val horizontal = dock.orientation == LinearLayout.HORIZONTAL
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(5), dp(3), dp(5))
            setOnClickListener { action() }
            isClickable = true
            background = rounded(themeColors()[2], 12)
            contentDescription = caption
        }
        item.addView(label(glyph, 22, themeColors()[1], true).apply { gravity = Gravity.CENTER })
        item.addView(label(caption, 8, themeColors()[3], true).apply { gravity = Gravity.CENTER })
        val lp = if (horizontal) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, dp(58))
        if (horizontal) lp.setMargins(dp(3), 0, dp(3), 0) else lp.bottomMargin = dp(8)
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
    fun launchFreeformComponent(component: String, orientation: String, fallback: () -> Unit) {
        Thread {
            val rotationCommand = when (orientation) {
                "portrait" -> "settings put system accelerometer_rotation 0 && settings put system user_rotation 0"
                "system" -> "settings put system accelerometer_rotation 1"
                else -> "settings put system accelerometer_rotation 0 && settings put system user_rotation 1"
            }
            val command = "settings put global enable_freeform_support 1 && " +
                "settings put global force_resizable_activities 1 && " +
                rotationCommand + " && am start --windowingMode 5 -n " + shellQuote(component)
            val result = runRoot(command)
            val launched = result.contains("Starting:") && !result.contains("Error:") &&
                !result.contains("Exception") && result.contains("exit=0")
            activity.runOnUiThread {
                if (launched) {
                    Toast.makeText(activity, "Opened in a desktop window", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Freeform was blocked; opening normally", Toast.LENGTH_LONG).show()
                    fallback()
                }
            }
        }.apply { isDaemon = true; name = "MT-GNOME-FreeformLaunch"; start() }
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
