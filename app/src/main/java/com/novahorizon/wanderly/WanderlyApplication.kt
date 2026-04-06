package com.novahorizon.wanderly

import android.app.Application
import org.osmdroid.config.Configuration

class WanderlyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Required for OSMDroid to load map tiles without being blocked by servers
        Configuration.getInstance().userAgentValue = packageName
    }
}
