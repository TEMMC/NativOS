package com.nativOS.runtime

import android.content.Context
import android.os.Process
import android.util.Log
import com.nativOS.settings.NativOSPreferences
import java.io.File

/**
 * Manages CPU/GPU performance governors, OOM killer protection,
 * kernel VM memory tuning (swappiness, caches), and swapfile configuration.
 */
class PerformanceManager(private val context: Context) {

    companion object {
        private const val TAG = "NativOS.Performance"
        private const val SAVED_GOVERNORS_FILE = "saved_cpu_governors.conf"
        private const val SWAP_FILE_PATH = "/data/local/tmp/nativos_swap.img"
    }

    private val rootShell = RootShell(context)
    private val displayRefreshRateManager = DisplayRefreshRateManager(context)
    private val savedGovernorsFile: File get() = File(context.filesDir, SAVED_GOVERNORS_FILE)

    // ── OOM Killer Protection ──

    /**
     * Protect the given PIDs from Android's Low Memory Killer (LMK).
     * Setting /proc/$pid/oom_score_adj to -1000 makes the kernel never kill this process.
     */
    fun protectPid(pid: Int) {
        if (!rootShell.hasRoot() || pid <= 0) return
        try {
            val cmd = "if [ -d /proc/$pid ]; then echo -1000 > /proc/$pid/oom_score_adj 2>/dev/null; fi"
            rootShell.exec(cmd)
            Log.i(TAG, "Protected PID $pid from OOM killer (oom_score_adj = -1000)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set oom_score_adj for PID $pid: ${e.message}")
        }
    }

    /**
     * Protect current Android app process, X11 server, and Linux desktop session.
     */
    fun protectCurrentSession(sessionPid: Int? = null, x11Pid: Int? = null) {
        if (!rootShell.hasRoot()) return
        protectPid(Process.myPid())
        sessionPid?.let { protectPid(it) }
        x11Pid?.let { protectPid(it) }

        // Also protect key desktop processes inside chroot
        val protectDesktopCmd = """
            for p in $(pgrep -x phoc || true) $(pgrep -x phosh || true) $(pgrep -x dbus-daemon || true) $(pgrep -x squeekboard || true); do
                echo -1000 > /proc/${'$'}p/oom_score_adj 2>/dev/null || true
            done
        """.trimIndent()
        rootShell.exec(protectDesktopCmd)
    }

    // ── CPU & GPU Governors ──

    /**
     * Apply maximum performance profile:
     * - Save current CPU governors for restoration upon exit
     * - Set all CPU cores to 'performance' governor
     * - Raise minimum scaling frequency
     * - Set GPU devfreq governor to 'performance'
     */
    fun applyPerformanceProfile() {
        if (!rootShell.hasRoot()) return
        if (!NativOSPreferences.performanceModeEnabled(context)) {
            Log.i(TAG, "Performance mode is disabled in preferences; skipping governor override")
            return
        }

        try {
            // 1. Save existing governors if not already saved
            saveCurrentGovernors()

            // 2. Set all CPU cores to performance
            val cpuCmd = """
                for gov in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
                    if [ -f "${'$'}gov" ]; then
                        echo performance > "${'$'}gov" 2>/dev/null || true
                    fi
                done
            """.trimIndent()
            rootShell.exec(cpuCmd)

            // 3. Lock minimum CPU frequencies to eliminate frame-drop stutter
            val minFreqCmd = """
                for f in /sys/devices/system/cpu/cpu*/cpufreq; do
                    if [ -f "${'$'}f/cpuinfo_max_freq" ] && [ -f "${'$'}f/scaling_min_freq" ]; then
                        max=$(cat "${'$'}f/cpuinfo_max_freq" 2>/dev/null || echo 0)
                        # Set min freq to ~50% of max freq for responsiveness without extreme thermal spike
                        if [ "${'$'}max" -gt 0 ]; then
                            target=$((max / 2))
                            echo "${'$'}target" > "${'$'}f/scaling_min_freq" 2>/dev/null || true
                        fi
                    fi
                done
            """.trimIndent()
            rootShell.exec(minFreqCmd)

            // 4. GPU Devfreq governor (Qualcomm Adreno KGSL / Mali devfreq)
            val gpuCmd = """
                # Adreno KGSL
                if [ -f /sys/class/kgsl/kgsl-3d0/devfreq/governor ]; then
                    echo performance > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || true
                fi
                # Generic devfreq GPU
                for g in /sys/class/devfreq/*gpu*/governor; do
                    if [ -f "${'$'}g" ]; then
                        echo performance > "${'$'}g" 2>/dev/null || true
                    fi
                done
            """.trimIndent()
            rootShell.exec(gpuCmd)

            // 5. Apply kernel VM tuning
            tuneKernelVirtualMemory()

            // 6. Manage swap/zram if enabled
            if (NativOSPreferences.swapEnabled(context)) {
                ensureSwapActive()
            }

            // 7. Lock Adreno GPU memory bus and clock to eliminate micro-stutter
            if (NativOSPreferences.adrenoGpuBusLock(context)) {
                val adrenoLockCmd = """
                    if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                        echo 1 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null || true
                        echo 1 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null || true
                        echo 1 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null || true
                        echo 1 > /sys/class/kgsl/kgsl-3d0/force_no_nap 2>/dev/null || true
                        if [ -f /sys/class/kgsl/kgsl-3d0/min_clock_mhz ]; then
                            echo 650 > /sys/class/kgsl/kgsl-3d0/min_clock_mhz 2>/dev/null || \
                            echo 490 > /sys/class/kgsl/kgsl-3d0/min_clock_mhz 2>/dev/null || true
                        fi
                    fi
                """.trimIndent()
                rootShell.exec(adrenoLockCmd)
                Log.i(TAG, "Adreno KGSL memory bus and minimum clock locked")
            }

            // 8. Apply 120Hz display lock and touch edge optimizations
            displayRefreshRateManager.applyDisplayOptimizations()

            Log.i(TAG, "Applied full performance profile (CPU & GPU governors locked, VM tuned)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply performance profile: ${e.message}")
        }
    }

    /**
     * Restore original CPU governors that were active before NativOS session started.
     */
    fun restoreOriginalGovernors() {
        if (!rootShell.hasRoot()) return
        try {
            // Reset Adreno KGSL hardware bus and clock locks
            val adrenoResetCmd = """
                if [ -d /sys/class/kgsl/kgsl-3d0 ]; then
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_bus_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_clk_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_rail_on 2>/dev/null || true
                    echo 0 > /sys/class/kgsl/kgsl-3d0/force_no_nap 2>/dev/null || true
                    if [ -f /sys/class/kgsl/kgsl-3d0/min_clock_mhz ]; then
                        echo 266 > /sys/class/kgsl/kgsl-3d0/min_clock_mhz 2>/dev/null || true
                    fi
                fi
            """.trimIndent()
            rootShell.exec(adrenoResetCmd)

            // Restore display refresh rate and touch edge settings
            displayRefreshRateManager.restoreOriginalSettings()

            if (savedGovernorsFile.exists()) {
                val lines = savedGovernorsFile.readLines()
                for (line in lines) {
                    val parts = line.split("=")
                    if (parts.size == 2) {
                        val path = parts[0].trim()
                        val governor = parts[1].trim()
                        if (governor.isNotEmpty() && File(path).exists()) {
                            rootShell.exec("echo $governor > $path 2>/dev/null || true")
                        }
                    }
                }
                savedGovernorsFile.delete()
                Log.i(TAG, "Restored original CPU governors from backup")
            } else {
                // Fallback to schedutil or powersave
                val fallbackCmd = """
                    for gov in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
                        if [ -f "${'$'}gov" ]; then
                            echo schedutil > "${'$'}gov" 2>/dev/null || echo interactive > "${'$'}gov" 2>/dev/null || true
                        fi
                    done
                    # Reset GPU governor to msm-adreno-tz or simple_ondemand
                    if [ -f /sys/class/kgsl/kgsl-3d0/devfreq/governor ]; then
                        echo msm-adreno-tz > /sys/class/kgsl/kgsl-3d0/devfreq/governor 2>/dev/null || true
                    fi
                """.trimIndent()
                rootShell.exec(fallbackCmd)
                Log.i(TAG, "Restored default governors via fallback")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore CPU governors: ${e.message}")
        }
    }

    private fun saveCurrentGovernors() {
        if (savedGovernorsFile.exists()) return
        val out = StringBuilder()
        val list = rootShell.exec("ls /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null").lines()
        for (path in list) {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) continue
            val current = rootShell.exec("cat $trimmed 2>/dev/null").trim()
            if (current.isNotEmpty() && current != "performance") {
                out.append("$trimmed=$current\n")
            }
        }
        if (out.isNotEmpty()) {
            savedGovernorsFile.writeText(out.toString())
        }
    }

    // ── Kernel VM Tuning ──

    /**
     * Tune Linux virtual memory subsystem for desktop apps under memory pressure:
     * - Increase swappiness to aggressively use ZRAM / swap
     * - Decrease vfs_cache_pressure to cache directory inodes
     * - Optimize dirty ratios to avoid disk I/O freezes
     */
    fun tuneKernelVirtualMemory() {
        if (!rootShell.hasRoot()) return
        try {
            val vmCmd = """
                sysctl -w vm.swappiness=100 2>/dev/null || echo 100 > /proc/sys/vm/swappiness 2>/dev/null || true
                sysctl -w vm.vfs_cache_pressure=50 2>/dev/null || echo 50 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null || true
                sysctl -w vm.dirty_ratio=10 2>/dev/null || echo 10 > /proc/sys/vm/dirty_ratio 2>/dev/null || true
                sysctl -w vm.dirty_background_ratio=5 2>/dev/null || echo 5 > /proc/sys/vm/dirty_background_ratio 2>/dev/null || true
                sysctl -w vm.extra_free_kbytes=20480 2>/dev/null || true
            """.trimIndent()
            rootShell.exec(vmCmd)
            Log.i(TAG, "Kernel VM parameters tuned for desktop workload")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to tune kernel VM: ${e.message}")
        }
    }

    // ── Swap Management ──

    /**
     * Ensure system has swap or create a dedicated 2GB swapfile on fast internal storage.
     */
    fun ensureSwapActive() {
        if (!rootShell.hasRoot()) return
        try {
            val swaps = rootShell.exec("cat /proc/swaps 2>/dev/null").lines()
            val hasActiveSwap = swaps.any { line -> line.startsWith("/") && !line.contains("Filename") }

            if (hasActiveSwap) {
                Log.i(TAG, "Active swap already present (ZRAM or swapfile)")
                return
            }

            // If no swap is active, attempt to create and enable a 2GB swapfile
            val createSwapCmd = """
                if [ ! -f $SWAP_FILE_PATH ]; then
                    dd if=/dev/zero of=$SWAP_FILE_PATH bs=1M count=2048 2>/dev/null &&
                    chmod 600 $SWAP_FILE_PATH &&
                    mkswap $SWAP_FILE_PATH >/dev/null 2>&1
                fi
                if [ -f $SWAP_FILE_PATH ]; then
                    swapon $SWAP_FILE_PATH 2>/dev/null || true
                fi
            """.trimIndent()
            rootShell.exec(createSwapCmd)
            Log.i(TAG, "Initialized and activated dedicated 2GB swapfile at $SWAP_FILE_PATH")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure swap active: ${e.message}")
        }
    }

    /**
     * Optimize process scheduling priority for interactive rendering.
     */
    fun boostProcessPriority(pid: Int) {
        if (!rootShell.hasRoot() || pid <= 0) return
        try {
            rootShell.exec("renice -n -10 -p $pid 2>/dev/null || true")
            rootShell.exec("ionice -c 2 -n 0 -p $pid 2>/dev/null || true")
        } catch (e: Exception) {
            Log.w(TAG, "Could not boost priority for PID $pid: ${e.message}")
        }
    }
}
