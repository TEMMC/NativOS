package com.nativOS.runtime

import android.content.Context
import android.util.Log
import com.nativOS.settings.NativOSPreferences
import java.io.File

/**
 * Manages display refresh rate (locking 120 Hz AMOLED panel) and touch controller
 * parameters (FocalTech fts_ts edge rejection and low-latency sampling) on Xiaomi / Qualcomm devices.
 */
class DisplayRefreshRateManager(private val context: Context) {

    companion object {
        private const val TAG = "NativOS.DisplayManager"
        private const val SAVED_REFRESH_FILE = "saved_refresh_rate.conf"
        private const val FTS_TOUCH_DIR = "/sys/devices/platform/soc/4c88000.i2c/i2c-2/2-0038"
    }

    private val rootShell = RootShell(context)
    private val savedRefreshFile: File get() = File(context.filesDir, SAVED_REFRESH_FILE)

    /**
     * Lock screen refresh rate to 120 Hz and tune touch edge responsiveness for desktop usage.
     */
    fun applyDisplayOptimizations() {
        if (!rootShell.hasRoot()) return
        if (!NativOSPreferences.lock120HzAndTouchBoost(context)) {
            Log.d(TAG, "120Hz and Touch boost disabled in preferences")
            return
        }

        try {
            // 1. Save original refresh rate settings if not already saved
            saveCurrentRefreshRate()

            // 2. Lock 120Hz on Android / MIUI SurfaceFlinger
            val refreshCmd = """
                settings put system min_refresh_rate 120.0 2>/dev/null || true
                settings put system peak_refresh_rate 120.0 2>/dev/null || true
                settings put system user_refresh_rate 120 2>/dev/null || true
            """.trimIndent()
            rootShell.exec(refreshCmd)
            Log.i(TAG, "Display refresh rate locked to 120.0 Hz")

            // 3. FocalTech / Xiaomi touchscreen optimizations for desktop UI:
            // - fts_edge_mode 0: disable edge deadzone rejection so window borders/taskbar buttons respond immediately
            // - fts_palm_mode 0: prevent false palm rejection during fine mouse/cursor manipulation
            val touchCmd = """
                if [ -d "$FTS_TOUCH_DIR" ]; then
                    echo 0 > "$FTS_TOUCH_DIR/fts_edge_mode" 2>/dev/null || true
                    echo 0 > "$FTS_TOUCH_DIR/fts_palm_mode" 2>/dev/null || true
                fi
                # Generic Xiaomi touch nodes if present
                for t in /sys/class/touch/touch_dev/bump_sample_rate /proc/touchpanel/game_switch_enable; do
                    if [ -f "${'$'}t" ]; then
                        echo 1 > "${'$'}t" 2>/dev/null || true
                    fi
                done
            """.trimIndent()
            rootShell.exec(touchCmd)
            Log.i(TAG, "Touchscreen edge sensitivity and high-rate sampling optimized")
        } catch (e: Exception) {
            Log.w(TAG, "Error applying display optimizations: ${e.message}")
        }
    }

    /**
     * Restore original refresh rate and touch behavior when desktop session finishes.
     */
    fun restoreOriginalSettings() {
        if (!rootShell.hasRoot()) return
        try {
            var minRate = "60.0"
            var peakRate = "120.0"
            if (savedRefreshFile.exists()) {
                val lines = savedRefreshFile.readLines()
                for (line in lines) {
                    val parts = line.split("=")
                    if (parts.size == 2) {
                        when (parts[0].trim()) {
                            "min_refresh_rate" -> minRate = parts[1].trim()
                            "peak_refresh_rate" -> peakRate = parts[1].trim()
                        }
                    }
                }
                savedRefreshFile.delete()
            }

            val restoreCmd = """
                settings put system min_refresh_rate $minRate 2>/dev/null || true
                settings put system peak_refresh_rate $peakRate 2>/dev/null || true
                if [ -d "$FTS_TOUCH_DIR" ]; then
                    echo 1 > "$FTS_TOUCH_DIR/fts_edge_mode" 2>/dev/null || true
                    echo 1 > "$FTS_TOUCH_DIR/fts_palm_mode" 2>/dev/null || true
                fi
                for t in /sys/class/touch/touch_dev/bump_sample_rate /proc/touchpanel/game_switch_enable; do
                    if [ -f "${'$'}t" ]; then
                        echo 0 > "${'$'}t" 2>/dev/null || true
                    fi
                done
            """.trimIndent()
            rootShell.exec(restoreCmd)
            Log.i(TAG, "Restored original refresh rate ($minRate Hz) and touch settings")
        } catch (e: Exception) {
            Log.w(TAG, "Error restoring display settings: ${e.message}")
        }
    }

    private fun saveCurrentRefreshRate() {
        if (savedRefreshFile.exists()) return
        try {
            val min = rootShell.exec("settings get system min_refresh_rate 2>/dev/null").trim()
            val peak = rootShell.exec("settings get system peak_refresh_rate 2>/dev/null").trim()
            val content = buildString {
                append("min_refresh_rate=").append(if (min.isNotEmpty() && min != "null") min else "60.0").append("\n")
                append("peak_refresh_rate=").append(if (peak.isNotEmpty() && peak != "null") peak else "120.0").append("\n")
            }
            savedRefreshFile.writeText(content)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save current refresh rate: ${e.message}")
        }
    }
}
