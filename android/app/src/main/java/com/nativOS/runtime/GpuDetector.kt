package com.nativOS.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.nativOS.settings.NativOSPreferences
import java.io.File

object GpuDetector {
    private const val TAG = "NativOS.GpuDetector"

    data class GpuInfo(
        val modelName: String,
        val vendor: String,
        val recommendedDriver: String,
        val description: String,
        val hasVulkan: Boolean
    )

    fun detect(context: Context): GpuInfo {
        val hasVulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.lowercase() else ""

        val isQualcomm = File("/dev/kgsl-3d0").exists() ||
                hardware.contains("qcom") || hardware.contains("qualcomm") ||
                board.contains("qcom") || soc.contains("sm") || soc.contains("snapdragon")

        val isMali = File("/dev/mali0").exists() ||
                hardware.contains("mt") || hardware.contains("mali") ||
                hardware.contains("exynos") || hardware.contains("tensor") ||
                soc.contains("dimensity") || soc.contains("tensor")

        return when {
            isQualcomm -> GpuInfo(
                modelName = "Qualcomm Adreno",
                vendor = "Qualcomm",
                recommendedDriver = "turnip",
                description = "Hardware Vulkan acceleration via Turnip (Freedreno) + Zink",
                hasVulkan = hasVulkan
            )
            isMali -> GpuInfo(
                modelName = "ARM Mali / Google Tensor",
                vendor = "ARM / MediaTek / Samsung",
                recommendedDriver = if (hasVulkan) "zink" else "virgl",
                description = if (hasVulkan) "Generic Vulkan acceleration via Zink" else "Host-accelerated VirGL / LLVMpipe",
                hasVulkan = hasVulkan
            )
            else -> GpuInfo(
                modelName = "Standard SoC (${Build.HARDWARE})",
                vendor = "Generic",
                recommendedDriver = "software",
                description = "High-performance multi-threaded Mesa LLVMpipe software rasterizer",
                hasVulkan = hasVulkan
            )
        }
    }

    fun resolveEffectiveDriver(context: Context): String {
        val pref = NativOSPreferences.gpuDriver(context)
        if (pref != "auto") return pref
        val detected = detect(context)
        Log.i(TAG, "Auto-detected GPU: ${detected.modelName} -> Driver: ${detected.recommendedDriver}")
        return detected.recommendedDriver
    }
}
