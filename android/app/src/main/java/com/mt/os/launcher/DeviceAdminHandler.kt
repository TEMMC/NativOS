package com.mt.os.launcher

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device admin receiver for kiosk mode capabilities.
 *
 * When MT-OS is set as Device Owner via:
 *   adb shell dpm set-device-owner com.mt.os/.launcher.DeviceAdminHandler
 *
 * It can:
 * - Disable the lockscreen
 * - Pin the app (lock task mode)
 * - Prevent user from leaving MT-OS without the escape hatch
 */
class DeviceAdminHandler : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "MT-OS.DeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled — MT-OS has kiosk capabilities")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin disabled")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Log.i(TAG, "Entering lock task mode: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.i(TAG, "Exiting lock task mode")
    }
}
