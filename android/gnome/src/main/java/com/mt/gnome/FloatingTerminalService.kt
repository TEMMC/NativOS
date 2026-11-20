package com.mt.gnome

import android.app.Service
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        content?.let { view -> try { if (view.isAttachedToWindow) wm.removeView(view) } catch (_: Exception) {} }
        content = null
        active = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var active: FloatingTerminalService? = null

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
