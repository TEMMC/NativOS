package com.nativOS.runtime

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.nativOS.settings.NativOSPreferences

object DisplayController {
    private const val TAG = "NativOS.Display"

    /**
     * Inspect display hardware and return the maximum supported refresh rate.
     */
    fun getMaxSupportedRefreshRate(context: Context): Float {
        return try {
            val modes = getSupportedModes(context)
            modes.maxOfOrNull { it.refreshRate } ?: 60f
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine refresh rate modes", e)
            60f
        }
    }

    private fun getSupportedModes(context: Context): List<Display.Mode> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return emptyList()
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display ?: @Suppress("DEPRECATION") windowManager.defaultDisplay
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        return display?.supportedModes?.toList() ?: emptyList()
    }

    /**
     * Apply maximum available refresh rate (e.g. 90Hz, 120Hz, 144Hz) to the activity window.
     */
    fun applyRefreshRate(activity: Activity) {
        val enabled = NativOSPreferences.highRefreshRate(activity)
        try {
            val modes = getSupportedModes(activity)
            if (modes.isEmpty()) return

            if (enabled) {
                val maxMode = modes.maxByOrNull { it.refreshRate }
                if (maxMode != null && maxMode.refreshRate > 65f) {
                    activity.window.attributes = activity.window.attributes.apply {
                        preferredDisplayModeId = maxMode.modeId
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            preferredRefreshRate = maxMode.refreshRate
                        }
                    }
                    Log.i(TAG, "Applied high refresh rate: ${maxMode.refreshRate} Hz (mode ${maxMode.modeId})")
                    return
                }
            }

            // Default / 60Hz mode
            activity.window.attributes = activity.window.attributes.apply {
                preferredDisplayModeId = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    preferredRefreshRate = 0f
                }
            }
            Log.i(TAG, "Applied standard refresh rate")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply refresh rate", e)
        }
    }

    /**
     * Computes the display scale (e.g. 1.0, 1.25, 1.5, 2.0) based on preference or screen DPI.
     */
    fun computeScale(context: Context): Float {
        val preferred = NativOSPreferences.displayScale(context)
        if (preferred > 0.5f) return preferred

        val dpi = context.resources.displayMetrics.densityDpi
        return when {
            dpi >= 540 -> 3.0f
            dpi >= 440 -> 2.5f
            dpi >= 340 -> 2.0f
            dpi >= 260 -> 1.5f
            dpi >= 200 -> 1.25f
            else -> 1.0f
        }
    }
}
