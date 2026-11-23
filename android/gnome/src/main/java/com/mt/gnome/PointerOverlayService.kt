package com.mt.gnome

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs

/** Full-screen touch-to-mouse layer. Touches anywhere on the display move and operate the desktop pointer. */
class PointerOverlayService : Service() {
    private var wm: WindowManager? = null
    private var touchSurface: View? = null
    private var cursor: PointerCursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var pointerX = 0f
    private var pointerY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var twoFinger = false
    private var lastTwoFingerY = 0f
    private var accumulatedScroll = 0f
    private var downAt = 0L
    private var lastTapAt = 0L
    private var accessibilityWarningShown = false
    private val sensitivity = 1.35f
    private val doubleTapMs = 280L
    private val longPressMs = 550L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        removeOverlays()
        if (getSharedPreferences("mt_gnome", MODE_PRIVATE).getString("input_mode", "direct") != "trackpad") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Display over other apps for MT-GNOME pointer", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!MTGNOMEAccessibilityService.isConnected()) {
            Toast.makeText(this, "Enable MT-GNOME Pointer in Accessibility settings for mouse clicks and scrolling", Toast.LENGTH_LONG).show()
        }
        val size = screenSize()
        pointerX = size.x * 0.5f
        pointerY = size.y * 0.5f
        addCursor()
        addFullScreenTouchSurface()
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
        val view = PointerCursorView(this)
        val params = WindowManager.LayoutParams(
            dp(34), dp(44), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
            Toast.makeText(this, "Could not display mouse pointer: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun addFullScreenTouchSurface() {
        val view = View(this).apply {
            // Transparent and full-screen: every touch position becomes a relative mouse movement.
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            wm?.addView(view, params)
            touchSurface = view
        } catch (error: Exception) {
            Toast.makeText(this, "Could not activate full-screen pointer: ${error.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                moved = false
                twoFinger = false
                accumulatedScroll = 0f
                downAt = android.os.SystemClock.uptimeMillis()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    twoFinger = true
                    moved = true
                    lastTwoFingerY = (event.getY(0) + event.getY(1)) / 2f
                    accumulatedScroll = 0f
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && event.pointerCount >= 2) {
                    val centerY = (event.getY(0) + event.getY(1)) / 2f
                    val delta = centerY - lastTwoFingerY
                    accumulatedScroll += delta
                    lastTwoFingerY = centerY
                    return true
                }
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                if (abs(event.rawX - downX) + abs(event.rawY - downY) > dp(5)) moved = true
                if (moved) movePointer(pointerX + dx * sensitivity, pointerY + dy * sensitivity)
                lastX = event.rawX
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (twoFinger && abs(accumulatedScroll) > dp(5)) sendScroll(accumulatedScroll)
                twoFinger = false
                return true
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = android.os.SystemClock.uptimeMillis() - downAt
                when {
                    twoFinger && abs(accumulatedScroll) > dp(5) -> sendScroll(accumulatedScroll)
                    !moved && elapsed >= longPressMs -> sendLongPress()
                    !moved -> {
                        val now = android.os.SystemClock.uptimeMillis()
                        val doubleClick = now - lastTapAt <= doubleTapMs
                        sendClick()
                        if (doubleClick) sendClick()
                        lastTapAt = if (doubleClick) 0L else now
                    }
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                moved = false
                twoFinger = false
                return true
            }
        }
        return true
    }

    private fun sendClick() {
        if (!MTGNOMEAccessibilityService.isConnected()) {
            warnAccessibility()
            return
        }
        if (!MTGNOMEAccessibilityService.tap(pointerX, pointerY)) warnAccessibility()
    }

    private fun sendLongPress() {
        if (!MTGNOMEAccessibilityService.isConnected()) {
            warnAccessibility()
            return
        }
        if (!MTGNOMEAccessibilityService.longPress(pointerX, pointerY)) warnAccessibility()
    }

    private fun sendScroll(delta: Float) {
        if (!MTGNOMEAccessibilityService.isConnected()) {
            warnAccessibility()
            return
        }
        val distance = (delta * 2.0f).coerceIn(-screenSize().y * 0.75f, screenSize().y * 0.75f)
        val endY = (pointerY - distance).coerceIn(0f, (screenSize().y - dp(12)).toFloat())
        MTGNOMEAccessibilityService.swipe(pointerX, pointerY, pointerX, endY, 240L)
    }

    private fun warnAccessibility() {
        if (accessibilityWarningShown) return
        accessibilityWarningShown = true
        Toast.makeText(this, "Enable MT-GNOME Pointer in Android Accessibility settings to use mouse clicks", Toast.LENGTH_LONG).show()
    }

    private fun movePointer(x: Float, y: Float) {
        val size = screenSize()
        pointerX = x.coerceIn(0f, (size.x - dp(8)).coerceAtLeast(0).toFloat())
        pointerY = y.coerceIn(0f, (size.y - dp(8)).coerceAtLeast(0).toFloat())
        cursorParams?.let { params ->
            params.x = pointerX.toInt()
            params.y = pointerY.toInt()
            try { cursor?.let { wm?.updateViewLayout(it, params) } } catch (_: Exception) {}
        }
    }

    private fun removeOverlays() {
        try { touchSurface?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { cursor?.let { wm?.removeView(it) } } catch (_: Exception) {}
        touchSurface = null
        cursor = null
        cursorParams = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        removeOverlays()
        wm = null
        super.onDestroy()
    }

    /** Conventional desktop arrow cursor, drawn as a crisp black outline with a white body. */
    private class PointerCursorView(context: android.content.Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2.2f
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        private val arrow = Path().apply {
            moveTo(2f, 2f)
            lineTo(2f, 34f)
            lineTo(10f, 26f)
            lineTo(16f, 39f)
            lineTo(22f, 36f)
            lineTo(16f, 23f)
            lineTo(28f, 22f)
            close()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.scale(width / 34f, height / 44f)
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x99000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(arrow, shadow)
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
            canvas.restore()
        }
    }
}
