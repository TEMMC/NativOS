package com.nativOS.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Safety watchdog and emergency failsafe for SurfaceFlinger.
 *
 * When Direct DRM mode executes `stop surfaceflinger` to release /dev/dri/card0,
 * this watchdog guarantees that:
 * 1. If the desktop compositor crashes or fails to initialize within 8 seconds,
 *    SurfaceFlinger is automatically restarted so the phone never gets stuck on a black screen.
 * 2. If the user leaves KioskActivity (onPause/onStop/onDestroy), SurfaceFlinger
 *    is immediately restored.
 * 3. Any external crash triggers immediate restoration of the Android UI.
 */
object SurfaceFlingerWatchdog {

    private const val TAG = "NativOS.SFWatchdog"
    private const val DEFAULT_TIMEOUT_MS = 8_000L

    private val isSurfaceFlingerStopped = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null

    /**
     * Call immediately before or right after executing `stop surfaceflinger`.
     * Arms a failsafe timer that will automatically restart SurfaceFlinger if not disarmed.
     */
    @Synchronized
    fun armWatchdog(context: Context, rootShell: RootShell, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        isSurfaceFlingerStopped.set(true)
        disarmWatchdog()

        val runnable = Runnable {
            synchronized(this) {
                if (isSurfaceFlingerStopped.get()) {
                    Log.w(TAG, "WATCHDOG TIMEOUT ($timeoutMs ms): Session did not confirm ready. Restoring SurfaceFlinger immediately!")
                    restoreSurfaceFlinger(context, rootShell)
                }
            }
        }
        watchdogRunnable = runnable
        mainHandler.postDelayed(runnable, timeoutMs)
        Log.i(TAG, "SurfaceFlinger watchdog armed for ${timeoutMs}ms")
    }

    /**
     * Call once the desktop session confirms it has successfully claimed the display.
     * Cancels the timeout, but keeps the state tracked so onStop/onDestroy can still restore it.
     */
    @Synchronized
    fun disarmWatchdog() {
        watchdogRunnable?.let {
            mainHandler.removeCallbacks(it)
            watchdogRunnable = null
            Log.d(TAG, "SurfaceFlinger watchdog timer disarmed (desktop alive)")
        }
    }

    /**
     * Restart SurfaceFlinger if it was stopped by NativOS.
     */
    @Synchronized
    fun restoreSurfaceFlinger(context: Context, rootShell: RootShell) {
        disarmWatchdog()
        isSurfaceFlingerStopped.set(false)
        try {
            Log.i(TAG, "Restoring SurfaceFlinger...")
            rootShell.exec("start surfaceflinger")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart SurfaceFlinger: ${e.message}")
        }
    }

    /**
     * Unconditional failsafe: verify if SurfaceFlinger is running, and restart it if stopped.
     * Can be invoked from Activity lifecycle callbacks (onPause, onStop, onDestroy).
     */
    fun ensureSurfaceFlingerRunning(context: Context) {
        if (!isSurfaceFlingerStopped.get()) return
        Thread {
            try {
                val shell = RootShell(context)
                if (shell.hasRoot()) {
                    val status = shell.exec("getprop init.svc.surfaceflinger").trim()
                    if (status == "stopped") {
                        Log.w(TAG, "SurfaceFlinger is stopped; restoring via lifecycle failsafe!")
                        restoreSurfaceFlinger(context, shell)
                    } else {
                        isSurfaceFlingerStopped.set(false)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in ensureSurfaceFlingerRunning: ${e.message}")
            }
        }.start()
    }
}
