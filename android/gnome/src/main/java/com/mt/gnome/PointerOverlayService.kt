package com.mt.gnome

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** System-wide touchpad and pointer overlays. Clicks are sent through the enabled accessibility service. */
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
    private var lastTwoFingerY = 0f
    private var accessibilityWarningShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        removeOverlays()
        val mode = getSharedPreferences("mt_gnome", MODE_PRIVATE).getString("input_mode", "direct")
        if (mode != "trackpad") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps for MT-GNOME pointer", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!MTGNOMEAccessibilityService.isConnected()) {
            Toast.makeText(this, "Pointer movement will work; enable MT-GNOME Pointer in Accessibility settings for clicks", Toast.LENGTH_LONG).show()
        }
        val size = screenSize()
        pointerX = size.x * 0.52f
        pointerY = size.y * 0.48f
        addCursor()
        addTrackpad()
        return START_STICKY
    }

    private fun screenSize(): Point {
        val size = Point()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealSize(size)
        if (size.x <= 0 || size.y <= 0) {
            size.x = resources.displayMetrics.widthPixels
            size.y = resources.displayMetrics.heightPixels
        }
        return size
    }

    private fun addCursor() {
        val view = TextView(this).apply {
            text = "➤"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(dp(5).toFloat(), dp(1).toFloat(), dp(2).toFloat(), Color.BLACK)
            setPadding(0, 0, 0, 0)
            isClickable = false
            isFocusable = false
        }
        val params = WindowManager.LayoutParams(
            dp(38), dp(44), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pointerX.toInt()
            y = pointerY.toInt()
        }
        try {
            wm?.addView(view, params)
            cursor = view
            cursorParams = params
        } catch (error: Exception) {
            Toast.makeText(this, "Could not display pointer: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun addTrackpad() {
        val view = TextView(this).apply {
            text = "TRACKPAD\n\nMove finger • Tap to click"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE62B3040.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xAA789BFF.toInt())
            }
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startPointerX = pointerX
                        startPointerY = pointerY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount >= 2) {
                            lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                            moved = true
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (event.pointerCount >= 2) {
                            val averageY = (event.getY(0) + event.getY(1)) / 2f
                            val delta = averageY - lastTwoFingerY
                            if (abs(delta) > dp(1)) {
                                moved = true
                                if (MTGNOMEAccessibilityService.isConnected()) {
                                    MTGNOMEAccessibilityService.swipe(
                                        pointerX, pointerY,
                                        pointerX, (pointerY - delta * 1.4f).coerceIn(0f, (screenSize().y - dp(12)).toFloat()),
                                        110L
                                    )
                                }
                            }
                            lastTwoFingerY = averageY
                        } else {
                            val dx = event.rawX - downX
                            val dy = event.rawY - downY
                            if (abs(dx) + abs(dy) > dp(3)) moved = true
                            movePointer(startPointerX + dx * 1.8f, startPointerY + dy * 1.8f)
                        }
                        true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        moved = true
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) sendClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> true
                }
            }
        }
        val params = WindowManager.LayoutParams(
            dp(178), dp(126), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(14)
            y = dp(20)
        }
        try {
            wm?.addView(view, params)
            pad = view
        } catch (error: Exception) {
            Toast.makeText(this, "Could not display trackpad: ${error.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun sendClick() {
        if (!MTGNOMEAccessibilityService.isConnected()) {
            if (!accessibilityWarningShown) {
                accessibilityWarningShown = true
                Toast.makeText(this, "Enable MT-GNOME Pointer in Android Accessibility settings to click", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!MTGNOMEAccessibilityService.tap(pointerX, pointerY)) {
            Toast.makeText(this, "Android rejected the pointer click. Re-enable MT-GNOME Pointer in Accessibility settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun movePointer(x: Float, y: Float) {
        val size = screenSize()
        pointerX = x.coerceIn(0f, (size.x - dp(12)).coerceAtLeast(0).toFloat())
        pointerY = y.coerceIn(0f, (size.y - dp(12)).coerceAtLeast(0).toFloat())
        cursorParams?.let { params ->
            params.x = pointerX.toInt()
            params.y = pointerY.toInt()
            try {
                cursor?.let { wm?.updateViewLayout(it, params) }
            } catch (_: Exception) {}
        }
    }

    private fun removeOverlays() {
        try { pad?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { cursor?.let { wm?.removeView(it) } } catch (_: Exception) {}
        pad = null
        cursor = null
        cursorParams = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        removeOverlays()
        wm = null
        super.onDestroy()
    }
}
