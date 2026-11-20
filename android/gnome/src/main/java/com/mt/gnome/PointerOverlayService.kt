package com.mt.gnome

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** System-wide trackpad and pointer overlays. Gesture injection is delegated to the approved accessibility service. */
class PointerOverlayService : Service() {
    private var wm: WindowManager? = null
    private var pad: View? = null
    private var cursor: TextView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var pointerX = 0f
    private var pointerY = 0f
    private var downX = 0f
    private var downY = 0f
    private var startPointerX = 0f
    private var startPointerY = 0f
    private var moved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val mode = getSharedPreferences("mt_gnome", MODE_PRIVATE).getString("input_mode", "direct")
        if (mode != "trackpad") { stopSelf(); return }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps for MT-GNOME pointer", Toast.LENGTH_LONG).show()
            stopSelf(); return
        }
        if (!MTGNOMEAccessibilityService.isConnected()) {
            Toast.makeText(this, "Enable MT-GNOME Pointer in Android Accessibility settings", Toast.LENGTH_LONG).show()
        }
        val metrics = resources.displayMetrics
        pointerX = metrics.widthPixels * 0.52f
        pointerY = metrics.heightPixels * 0.48f
        addCursor()
        addTrackpad()
    }

    private fun addCursor() {
        val view = TextView(this).apply {
            text = "➤"; textSize = 27f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; setShadowLayer(5f, 1f, 2f, Color.BLACK)
            setPadding(0, 0, 0, 0); isClickable = false
        }
        val params = WindowManager.LayoutParams(
            dp(34), dp(40), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = pointerX.toInt(); y = pointerY.toInt() }
        try { wm?.addView(view, params); cursor = view; cursorParams = params } catch (_: Exception) {}
    }

    private fun addTrackpad() {
        val view = TextView(this).apply {
            text = "TRACKPAD\n\nMove • Tap"; textSize = 11f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xC92B3040.toInt()); cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x665E8CFF)
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startPointerX = pointerX; startPointerY = pointerY; moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) + abs(dy) > dp(3)) moved = true
                        movePointer(startPointerX + dx * 1.65f, startPointerY + dy * 1.65f)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            MTGNOMEAccessibilityService.tap(pointerX, pointerY)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        val params = WindowManager.LayoutParams(
            dp(164), dp(116), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(14); y = dp(20) }
        try { wm?.addView(view, params); pad = view } catch (_: Exception) { stopSelf() }
    }

    private fun movePointer(x: Float, y: Float) {
        val metrics = resources.displayMetrics
        pointerX = x.coerceIn(0f, (metrics.widthPixels - dp(10)).toFloat())
        pointerY = y.coerceIn(0f, (metrics.heightPixels - dp(10)).toFloat())
        cursorParams?.let {
            it.x = pointerX.toInt(); it.y = pointerY.toInt()
            try { cursor?.let { v -> wm?.updateViewLayout(v, it) } } catch (_: Exception) {}
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        try { pad?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { cursor?.let { wm?.removeView(it) } } catch (_: Exception) {}
        pad = null; cursor = null; wm = null
        super.onDestroy()
    }
}
