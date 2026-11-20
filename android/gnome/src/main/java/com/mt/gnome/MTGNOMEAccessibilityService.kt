package com.mt.gnome

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.os.Handler
import android.os.Looper

class MTGNOMEAccessibilityService : AccessibilityService() {
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
    }

    override fun onServiceConnected() { super.onServiceConnected(); active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }
}
