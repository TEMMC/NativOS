package com.nativOS.settings

import android.content.Context

object NativOSPreferences {
    enum class OperatingMode { DESKTOP_APP, HOME_LAUNCHER, DEGOOGLED }

    private const val FILE = "nativos_settings"
    private const val HIDE_SYSTEM_BARS = "hide_system_bars"
    private const val SHOW_ANDROID_APPS = "show_android_apps"
    private const val OPERATING_MODE = "operating_mode"

    fun hideSystemBars(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(HIDE_SYSTEM_BARS, false)

    fun setHideSystemBars(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(HIDE_SYSTEM_BARS, enabled).apply()
    }

    fun showAndroidApps(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(SHOW_ANDROID_APPS, true)

    fun setShowAndroidApps(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(SHOW_ANDROID_APPS, enabled).apply()
    }

    fun operatingMode(context: Context): OperatingMode {
        val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(OPERATING_MODE, OperatingMode.DESKTOP_APP.name)
        val migrated = if (stored == "MINIMAL_ANDROID") OperatingMode.DEGOOGLED.name else stored
        return runCatching { OperatingMode.valueOf(migrated.orEmpty()) }
            .getOrDefault(OperatingMode.DESKTOP_APP)
    }

    fun setOperatingMode(context: Context, mode: OperatingMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(OPERATING_MODE, mode.name).apply()
    }

    private const val PREFERRED_DISTRO = "preferred_distro"

    fun preferredDistro(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(PREFERRED_DISTRO, "alpine") ?: "alpine"

    fun setPreferredDistro(context: Context, distro: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(PREFERRED_DISTRO, distro).apply()
    }

    private const val HIGH_REFRESH_RATE = "high_refresh_rate"
    private const val DISPLAY_SCALE = "display_scale"
    private const val GPU_DRIVER = "gpu_driver"
    private const val SYSTEM_LANGUAGE = "system_language"
    private const val SOFTWARE_PROFILE = "software_profile"
    private const val SETUP_WIZARD_COMPLETED = "setup_wizard_completed"

    fun highRefreshRate(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(HIGH_REFRESH_RATE, true)

    fun setHighRefreshRate(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(HIGH_REFRESH_RATE, enabled).apply()
    }

    /** Display scale factor: 0f = auto, or 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f */
    fun displayScale(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(DISPLAY_SCALE, 0f)

    fun setDisplayScale(context: Context, scale: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putFloat(DISPLAY_SCALE, scale).apply()
    }

    fun gpuDriver(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(GPU_DRIVER, "auto") ?: "auto"

    fun setGpuDriver(context: Context, driver: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(GPU_DRIVER, driver).apply()
    }

    fun systemLanguage(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(SYSTEM_LANGUAGE, "system") ?: "system"

    fun setSystemLanguage(context: Context, lang: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(SYSTEM_LANGUAGE, lang).apply()
    }

    fun softwareProfile(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(SOFTWARE_PROFILE, "minimal") ?: "minimal"

    fun setSoftwareProfile(context: Context, profile: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(SOFTWARE_PROFILE, profile).apply()
    }

    private const val DESKTOP_ENVIRONMENT = "desktop_environment"
    private const val TOUCH_MODE = "touch_mode"

    fun desktopEnvironment(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(DESKTOP_ENVIRONMENT, "phosh") ?: "phosh"

    fun setDesktopEnvironment(context: Context, env: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(DESKTOP_ENVIRONMENT, env).apply()
    }

    /** Touch mode: "touch" (Multi-Touch direct 120Hz), "simulated" (Mouse clicks), "trackpad" (Trackpad cursor) */
    fun touchMode(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(TOUCH_MODE, "touch") ?: "touch"

    fun setTouchMode(context: Context, mode: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(TOUCH_MODE, mode).apply()
    }

    fun isSetupWizardCompleted(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(SETUP_WIZARD_COMPLETED, false)

    fun setSetupWizardCompleted(context: Context, completed: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(SETUP_WIZARD_COMPLETED, completed).apply()
    }

    // ── Performance & Memory ──
    private const val PERFORMANCE_MODE = "performance_mode"
    private const val SWAP_ENABLED = "swap_enabled"

    fun performanceModeEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(PERFORMANCE_MODE, true)

    fun setPerformanceModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(PERFORMANCE_MODE, enabled).apply()
    }

    fun swapEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(SWAP_ENABLED, false)

    fun setSwapEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(SWAP_ENABLED, enabled).apply()
    }

    // ── Storage Mode (.img vs directory) ──
    private const val STORAGE_MODE = "storage_mode"
    private const val STORAGE_FS_TYPE = "storage_fs_type"

    fun storageMode(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(STORAGE_MODE, "directory") ?: "directory"

    fun setStorageMode(context: Context, mode: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(STORAGE_MODE, mode).apply()
    }

    fun storageFsType(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(STORAGE_FS_TYPE, "ext4") ?: "ext4"

    fun setStorageFsType(context: Context, fsType: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(STORAGE_FS_TYPE, fsType).apply()
    }

    // ── Touch Calibration & Transformation ──
    private const val TOUCH_INVERT_X = "touch_invert_x"
    private const val TOUCH_INVERT_Y = "touch_invert_y"
    private const val TOUCH_SWAP_AXES = "touch_swap_axes"
    private const val TOUCH_ROTATION = "touch_rotation"
    private const val TOUCH_SCALE_X = "touch_scale_x"
    private const val TOUCH_SCALE_Y = "touch_scale_y"
    private const val TOUCH_OFFSET_X = "touch_offset_x"
    private const val TOUCH_OFFSET_Y = "touch_offset_y"

    fun touchInvertX(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(TOUCH_INVERT_X, false)

    fun setTouchInvertX(context: Context, invert: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(TOUCH_INVERT_X, invert).apply()
    }

    fun touchInvertY(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(TOUCH_INVERT_Y, false)

    fun setTouchInvertY(context: Context, invert: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(TOUCH_INVERT_Y, invert).apply()
    }

    fun touchSwapAxes(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(TOUCH_SWAP_AXES, false)

    fun setTouchSwapAxes(context: Context, swap: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(TOUCH_SWAP_AXES, swap).apply()
    }

    fun touchRotationCorrection(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(TOUCH_ROTATION, 0)

    fun setTouchRotationCorrection(context: Context, degrees: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(TOUCH_ROTATION, degrees).apply()
    }

    fun touchScaleX(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(TOUCH_SCALE_X, 1.0f)

    fun setTouchScaleX(context: Context, scale: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putFloat(TOUCH_SCALE_X, scale).apply()
    }

    fun touchScaleY(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(TOUCH_SCALE_Y, 1.0f)

    fun setTouchScaleY(context: Context, scale: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putFloat(TOUCH_SCALE_Y, scale).apply()
    }

    fun touchOffsetX(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(TOUCH_OFFSET_X, 0.0f)

    fun setTouchOffsetX(context: Context, offset: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putFloat(TOUCH_OFFSET_X, offset).apply()
    }

    fun touchOffsetY(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(TOUCH_OFFSET_Y, 0.0f)

    fun setTouchOffsetY(context: Context, offset: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putFloat(TOUCH_OFFSET_Y, offset).apply()
    }

    fun resetTouchCalibration(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(TOUCH_INVERT_X, false)
            .putBoolean(TOUCH_INVERT_Y, false)
            .putBoolean(TOUCH_SWAP_AXES, false)
            .putInt(TOUCH_ROTATION, 0)
            .putFloat(TOUCH_SCALE_X, 1.0f)
            .putFloat(TOUCH_SCALE_Y, 1.0f)
            .putFloat(TOUCH_OFFSET_X, 0.0f)
            .putFloat(TOUCH_OFFSET_Y, 0.0f)
            .apply()
    }

    private const val RESOLUTION_SCALE_PERCENT = "resolution_scale_percent"
    private const val WLR_RENDERER = "wlr_renderer"
    private const val MESA_GLTHREAD = "mesa_glthread"

    /** Virtual resolution scale: 100 (Native), 80 (Balanced -36% fillrate), 67 (Performance -55% fillrate) */
    fun resolutionScalePercent(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(RESOLUTION_SCALE_PERCENT, 100)

    fun setResolutionScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(RESOLUTION_SCALE_PERCENT, percent).apply()
    }

    /** Compositor renderer: "auto" (GLES2 with pixman fallback), "gles2", "pixman" */
    fun wlrRenderer(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(WLR_RENDERER, "auto") ?: "auto"

    fun setWlrRenderer(context: Context, renderer: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(WLR_RENDERER, renderer).apply()
    }

    /** Multi-threaded OpenGL dispatch via Mesa glthread */
    fun mesaGlThread(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(MESA_GLTHREAD, true)

    fun setMesaGlThread(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(MESA_GLTHREAD, enabled).apply()
    }

    private const val ALSA_DIRECT_AUDIO = "alsa_direct_audio"
    private const val HW_VIDEO_DECODING = "hw_video_decoding"
    private const val DIRECT_DRM_MODE = "direct_drm_mode"

    /** Direct ALSA hardware audio bypassing PulseAudio TCP latency */
    fun alsaDirectAudio(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(ALSA_DIRECT_AUDIO, true)

    fun setAlsaDirectAudio(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(ALSA_DIRECT_AUDIO, enabled).apply()
    }

    /** Hardware video acceleration via Snapdragon Venus / V4L2 M2M */
    fun hardwareVideoDecoding(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(HW_VIDEO_DECODING, true)

    fun setHardwareVideoDecoding(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(HW_VIDEO_DECODING, enabled).apply()
    }

    /** Experimental Direct DRM/KMS mode: stop surfaceflinger and output to /dev/dri/card0 directly */
    fun directDrmMode(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(DIRECT_DRM_MODE, false)

    fun setDirectDrmMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(DIRECT_DRM_MODE, enabled).apply()
    }

    private const val LOCK_120HZ_TOUCH_BOOST = "lock_120hz_touch_boost"
    private const val ADRENO_GPU_BUS_LOCK = "adreno_gpu_bus_lock"

    /** Lock 120Hz refresh rate and tune touch edge/sampling for desktop */
    fun lock120HzAndTouchBoost(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(LOCK_120HZ_TOUCH_BOOST, true)

    fun setLock120HzAndTouchBoost(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(LOCK_120HZ_TOUCH_BOOST, enabled).apply()
    }

    /** Adreno GPU hardware memory bus lock and minimum clock lock */
    fun adrenoGpuBusLock(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(ADRENO_GPU_BUS_LOCK, true)

    fun setAdrenoGpuBusLock(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(ADRENO_GPU_BUS_LOCK, enabled).apply()
    }
}


