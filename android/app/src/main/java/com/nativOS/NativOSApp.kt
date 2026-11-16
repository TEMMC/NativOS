package com.mtOS

import android.app.Application
import android.util.Log

/**
 * MT-OS Application class.
 *
 * Initializes the bridge service infrastructure and chroot runtime
 * at application startup.
 */
class MT-OSApp : Application() {

    companion object {
        const val TAG = "MT-OS"

        @Volatile
        lateinit var instance: MT-OSApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "MT-OS application initialized")
    }
}
