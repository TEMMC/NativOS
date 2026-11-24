package com.mt.gnome

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.widget.TextView

class FloatingTerminalService : Service() {
    private lateinit var wm: WindowManager
    private var content: View? = null
    private var owner: TerminalActivity? = null
    private lateinit var params: WindowManager.LayoutParams
    private var bubble: View? = null
    private var minimized = false


    private fun startTerminalForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "mt_floating_terminal"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                channelId, "Floating MT Terminal", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the floating Linux terminal available above other apps" })
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder.setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("MT Terminal is floating")
            .setContentText("Your Linux shell stays available while you use other apps.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1701, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1701, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attachCurrentOverlay()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    private fun detachOverlay() {
        val mini = bubble
        bubble = null
        if (mini != null) try { if (::wm.isInitialized && mini.isAttachedToWindow) wm.removeView(mini) } catch (_: Exception) { }
        minimized = false
        val view = content
        content = null
        if (view != null) {
            try {
                if (::wm.isInitialized && view.isAttachedToWindow) wm.removeView(view)
            } catch (e: Exception) {
                android.util.Log.w("MT-GNOME", "Could not detach floating terminal view", e)
            }
        }
        if (owner?.let { TerminalActivity.hostActivity === it } == true) {
            TerminalActivity.overlayContent = null
        }
    }

    @Synchronized
    private fun attachCurrentOverlay() {
        if (!::wm.isInitialized) return
        val currentOwner = TerminalActivity.hostActivity ?: return
        // Check ownership before the attached-view fast path. Otherwise a stale
        // service can keep an old Activity's attached view after a rapid reopen.
        if (owner != null && owner !== currentOwner) detachOverlay()
        if (owner === currentOwner && (content?.isAttachedToWindow == true || bubble?.isAttachedToWindow == true)) return
        val view = TerminalActivity.overlayContent ?: return
        owner = currentOwner
        try {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            val metrics = resources.displayMetrics
            params = WindowManager.LayoutParams(
                (metrics.widthPixels * 0.94f).toInt(),
                (metrics.heightPixels * 0.82f).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (metrics.widthPixels - width) / 2
                y = (metrics.heightPixels - height) / 2
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            wm.addView(view, params)
            content = view
            minimized = false
        } catch (e: Exception) {
            content = null
            android.util.Log.e("MT-GNOME", "Could not attach floating terminal overlay", e)
            stopSelf()
        }
    }


    @Synchronized
    private fun minimizeWindow() {
        val view = content ?: return
        if (!view.isAttachedToWindow || minimized) return
        try {
            wm.removeView(view)
            val size = (56 * resources.displayMetrics.density).toInt()
            val chip = TextView(this).apply {
                text = "MT"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(48, 83, 125)); setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE) }
                elevation = 12f
                contentDescription = "Restore MT Terminal"
                setOnClickListener { restoreWindow() }
            }
            val miniParams = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.END; x = (12 * resources.displayMetrics.density).toInt(); y = resources.displayMetrics.heightPixels / 3
            }
            wm.addView(chip, miniParams); bubble = chip; minimized = true
        } catch (e: Exception) {
            android.util.Log.e("MT-GNOME", "Could not minimize terminal overlay", e)
            try { if (!view.isAttachedToWindow) wm.addView(view, params) } catch (_: Exception) { }
        }
    }

    @Synchronized
    private fun restoreWindow() {
        val view = content ?: return
        try { bubble?.let { if (it.isAttachedToWindow) wm.removeView(it) }; bubble = null; if (!view.isAttachedToWindow) wm.addView(view, params); minimized = false }
        catch (e: Exception) { android.util.Log.e("MT-GNOME", "Could not restore terminal overlay", e) }
    }

    private fun closeWindow(requester: TerminalActivity) {
        if (owner !== requester) return
        detachOverlay(); owner = null; stopSelf()
        requester.runOnUiThread { if (!requester.isFinishing) requester.finish() }
    }

    override fun onDestroy() {
        detachOverlay()
        if (active === this) active = null
        owner = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var active: FloatingTerminalService? = null

        @Synchronized
        fun detachOverlay(owner: TerminalActivity) {
            active?.let { service ->
                if (service.owner === owner) service.detachOverlay()
            }
        }


        @Synchronized
        fun minimizeOverlay(owner: TerminalActivity) { active?.let { if (it.owner === owner) it.minimizeWindow() } }

        @Synchronized
        fun updateBounds(x: Int, y: Int, width: Int, height: Int) {
            active?.let { service ->
                val view = service.content ?: return
                if (!view.isAttachedToWindow) return
                try {
                    val metrics = service.resources.displayMetrics
                    val minWidth = (280 * metrics.density).toInt()
                    val minHeight = (240 * metrics.density).toInt()
                    service.params.width = width.coerceIn(minWidth, metrics.widthPixels.coerceAtLeast(minWidth))
                    service.params.height = height.coerceIn(minHeight, metrics.heightPixels.coerceAtLeast(minHeight))
                    service.params.x = x.coerceIn(0, (metrics.widthPixels - service.params.width).coerceAtLeast(0))
                    service.params.y = y.coerceIn(0, (metrics.heightPixels - service.params.height).coerceAtLeast(0))
                    service.wm.updateViewLayout(view, service.params)
                } catch (e: Exception) {
                    android.util.Log.e("MT-GNOME", "Could not update floating terminal bounds", e)
                }
            }
        }

        @Synchronized
        fun closeOverlay(owner: TerminalActivity) {
            val service = active
            if (service != null && service.owner === owner) service.closeWindow(owner)
            else if (!owner.isFinishing) owner.finish()
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        startTerminalForeground()
        TerminalActivity.hostActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps to use the floating terminal", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        attachCurrentOverlay()
    }
}
