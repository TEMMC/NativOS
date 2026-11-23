package com.mt.gnome

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast

class FloatingTerminalService : Service() {
    private lateinit var wm: WindowManager
    private var content: View? = null
    private lateinit var params: WindowManager.LayoutParams


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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    private fun detachOverlay() {
        val view = content ?: return
        // Clear the reference first so a rapid reopen cannot reuse a view that is
        // being removed from the old Activity's window.
        content = null
        try {
            if (::wm.isInitialized && view.isAttachedToWindow) wm.removeViewImmediate(view)
        } catch (e: Exception) {
            android.util.Log.w("MT-GNOME", "Could not detach floating terminal view", e)
        }
    }

    override fun onDestroy() {
        detachOverlay()
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var active: FloatingTerminalService? = null

        @Synchronized
        fun detachOverlay() {
            active?.let { service ->
                service.detachOverlay()
            }
        }

        fun updatePosition(x: Int, y: Int) {
            active?.let { service ->
                service.content?.let { try {
                    service.params.x = x
                    service.params.y = y
                    service.wm.updateViewLayout(it, service.params)
                } catch (_: Exception) {} }
            }
        }

        fun updateSize(width: Int, height: Int) {
            active?.let { service ->
                service.content?.let { try {
                    service.params.width = width
                    service.params.height = height
                    service.wm.updateViewLayout(it, service.params)
                } catch (_: Exception) {} }
            }
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
        content = TerminalActivity.overlayContent
        val view = content ?: run { stopSelf(); return }
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
        try { wm.addView(view, params) } catch (e: Exception) {
            Toast.makeText(this, "Could not create floating terminal: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }
}
