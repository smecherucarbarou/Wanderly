package com.novahorizon.wanderly.observability

import android.os.StrictMode
import com.novahorizon.wanderly.BuildConfig

object StrictModeInitializer {
    fun enableForDebugBuild() {
        if (!BuildConfig.DEBUG) return

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
    }
}
