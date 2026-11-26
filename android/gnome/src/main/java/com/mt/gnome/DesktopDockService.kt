package com.mt.gnome

import android.app.Service
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Persistent application dock. Android displays this above other apps only after overlay approval. */
class DesktopDockService : Service() {
    private var manager: WindowManager? = null
    private var dock: LinearLayout? = null
    private var toggle: TextView? = null
    private var toggleParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.mt.gnome.DOCK_SHOW" -> { if (dock == null) buildDock(); getSharedPreferences("mt_gnome", MODE_PRIVATE).edit().putBoolean("dock_visible", true).apply() }
            "com.mt.gnome.DOCK_HIDE" -> { if (dock != null) { try { manager?.removeView(dock) } catch (_: Exception) {}; dock = null }; getSharedPreferences("mt_gnome", MODE_PRIVATE).edit().putBoolean("dock_visible", false).apply() }
            "com.mt.gnome.DOCK_TOGGLE" -> toggleDock()
            "com.mt.gnome.DOCK_TOGGLE_BUTTON_SHOW" -> { if (toggle == null) buildToggle(); getSharedPreferences("mt_gnome", MODE_PRIVATE).edit().putBoolean("dock_toggle_visible", true).apply() }
            "com.mt.gnome.DOCK_TOGGLE_BUTTON_HIDE" -> {
                try { toggle?.let { manager?.removeView(it) } } catch (_: Exception) {}
                toggle = null; toggleParams = null
                getSharedPreferences("mt_gnome", MODE_PRIVATE).edit().putBoolean("dock_toggle_visible", false).apply()
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (getSharedPreferences("mt_gnome", MODE_PRIVATE).getBoolean("dock_toggle_visible", true)) buildToggle()
        if (getSharedPreferences("mt_gnome", MODE_PRIVATE).getBoolean("dock_visible", true)) buildDock()
    }

    private fun buildToggle() {
        if (toggle != null) return
        val bubble = TextView(this).apply {
            text = "≡"; textSize = 23f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = rounded(Color.rgb(55, 67, 100), 16)
            elevation = dp(8).toFloat(); contentDescription = "Show or hide MT app bar"
            setOnClickListener { toggleDock() }
        }
        val params = WindowManager.LayoutParams(
            dp(46), dp(46), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.BOTTOM; x = dp(8); y = dp(90) }
        var lastX = 0f
        var lastY = 0f
        var dragged = false
        bubble.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX; val dy = event.rawY - lastY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(3)) dragged = true
                    if (dragged) {
                        params.x = (params.x + dx).toInt().coerceAtLeast(0)
                        params.y = (params.y - dy).toInt().coerceAtLeast(0)
                        try { manager?.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    lastX = event.rawX; lastY = event.rawY; true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) view.performClick(); true }
                else -> true
            }
        }
        toggle = bubble; toggleParams = params
        try { manager?.addView(bubble, params) } catch (_: Exception) { stopSelf() }
    }

    private fun toggleDock() {
        if (dock != null) {
            try { manager?.removeView(dock) } catch (_: Exception) {}
            dock = null
            getSharedPreferences("mt_gnome", MODE_PRIVATE).edit().putBoolean("dock_visible", false).apply()
        } else {
            buildDock()
            getSharedPreferences("mt_gnome", MODE_PRIVATE).edit().putBoolean("dock_visible", true).apply()
        }
    }
    private fun buildDock() {
        if (dock != null) return
        val dockPrefs = getSharedPreferences("mt_gnome", MODE_PRIVATE)
        val dockWidth = dockPrefs.getInt("dock_width", 66).coerceIn(52, 104)
        val dockOpacity = dockPrefs.getInt("dock_opacity", 96).coerceIn(35, 100)
        val itemWidth = (dockWidth - 14).coerceIn(38, 90)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(5), dp(9), dp(5), dp(9))
            background = rounded(Color.argb(dockOpacity * 255 / 100, 20, 22, 29), 18)
        }
        val hide = TextView(this).apply {
            text = "‹"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription = "Hide app bar"; setOnClickListener { toggleDock() }
        }
        root.addView(hide, LinearLayout.LayoutParams(dp(52), dp(36)))
        fun shortcut(glyph: String, title: String, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(7), dp(2), dp(7))
                setOnClickListener { action() }
                contentDescription = title
            }
            item.addView(TextView(this).apply {
                text = glyph; textSize = 21f; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(dp(42), dp(35)))
            item.addView(TextView(this).apply {
                text = title; textSize = 8f; gravity = Gravity.CENTER
                setTextColor(Color.LTGRAY); maxLines = 1
            })
            root.addView(item, LinearLayout.LayoutParams(dp(itemWidth), dp(54)))
        }
        shortcut("⌘", "Terminal") {
            launchComponent("com.mt.gnome", "com.mt.gnome.DistroCatalogActivity")
        }
        shortcut("⚙", "Settings") {
            val i = Intent(this, GnomeHomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            i.putExtra("open_settings", true)
            startActivity(i)
        }
        shortcut("▤", "Tools") {
            startActivity(Intent(this, DesktopToolsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val line = TextView(this).apply { setBackgroundColor(Color.argb(80,255,255,255)) }
        root.addView(line, LinearLayout.LayoutParams(dp(36), dp(1)).apply { topMargin=dp(4); bottomMargin=dp(4) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val scroll = ScrollView(this).apply { overScrollMode = ScrollView.OVER_SCROLL_NEVER }
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps: List<ResolveInfo> = packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        apps.forEach { info ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(2), dp(6), dp(2), dp(6))
                contentDescription = info.loadLabel(packageManager).toString()
                setOnClickListener { launchComponent(info.activityInfo.packageName, info.activityInfo.name) }
            }
            item.addView(ImageView(this).apply {
                setImageDrawable(info.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            item.addView(TextView(this).apply {
                text = info.loadLabel(packageManager).toString(); textSize = 7.5f
                setTextColor(Color.WHITE); gravity = Gravity.CENTER
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(dp(50), dp(15)))
            list.addView(item, LinearLayout.LayoutParams(dp(52), dp(55)))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(dp(itemWidth), 0, 1f))
        dock = root
        val params = WindowManager.LayoutParams(
            dp(dockWidth), WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = dp(5); y = 0
        }
        try { manager?.addView(root, params) } catch (_: Exception) { stopSelf() }
    }

    private fun launchComponent(pkg: String, cls: String) {
        Thread {
            val component = "$pkg/$cls"
            val command = "settings put global enable_freeform_support 1; settings put global force_resizable_activities 1; am start --windowingMode 5 -n '$component'"
            val result = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText()
                val code = p.waitFor()
                out + err + " exit=$code"
            } catch (e: Exception) { e.toString() }
            if (!result.contains("exit=0") || result.contains("Error:")) {
                val launch = packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch != null) try { startActivity(launch) } catch (_: Exception) {
                    android.os.Handler(mainLooper).post { Toast.makeText(this, "Could not launch $pkg", Toast.LENGTH_SHORT).show() }
                }
            }
        }.start()
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), Color.argb(35, 230, 230, 255))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try { dock?.let { manager?.removeView(it) } } catch (_: Exception) {}
        try { toggle?.let { manager?.removeView(it) } } catch (_: Exception) {}
        toggle = null; toggleParams = null; dock = null; manager = null
        super.onDestroy()
    }
}
