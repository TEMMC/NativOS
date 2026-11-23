package com.mt.gnome

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.content.Intent

class MTGNOMEAccessibilityService : AccessibilityService() {
    private val keyHandler = Handler(Looper.getMainLooper())
    private var volumeUpDown = false
    private var volumeDownDown = false
    private var chordTriggered = false
    private val chordAction = Runnable {
        if (volumeUpDown && volumeDownDown && !chordTriggered) {
            chordTriggered = true
            val prefs = getSharedPreferences("mt_gnome", MODE_PRIVATE)
            val enableTrackpad = prefs.getString("input_mode", "direct") != "trackpad"
            prefs.edit().putString("input_mode", if (enableTrackpad) "trackpad" else "direct").apply()
            if (enableTrackpad) startService(Intent(this, PointerOverlayService::class.java))
            else stopService(Intent(this, PointerOverlayService::class.java))
            android.widget.Toast.makeText(this, if (enableTrackpad) "Trackpad enabled" else "Direct touch enabled", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    companion object {
        @Volatile private var active: MTGNOMEAccessibilityService? = null
        fun isConnected(): Boolean = active != null
        fun tap(x: Float, y: Float, duration: Long = 70L): Boolean {
            val svc = active ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            return svc.dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
        }
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 180L): Boolean {
            val svc = active ?: return false
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            return svc.dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
        }
        fun longPress(x: Float, y: Float, duration: Long = 650L): Boolean {
            val svc = active ?: return false
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            return svc.dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) volumeUpDown = true else volumeDownDown = true
                if (volumeUpDown && volumeDownDown && !chordTriggered) keyHandler.postDelayed(chordAction, 3000L)
            }
            KeyEvent.ACTION_UP -> {
                keyHandler.removeCallbacks(chordAction)
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) volumeUpDown = false else volumeDownDown = false
                if (!volumeUpDown || !volumeDownDown) chordTriggered = false
            }
        }
        return true
    }
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
}
