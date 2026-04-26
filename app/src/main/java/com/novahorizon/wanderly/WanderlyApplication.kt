package com.novahorizon.wanderly

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.novahorizon.wanderly.data.PreferencesStore
import com.novahorizon.wanderly.workers.StreakWorker
import com.novahorizon.wanderly.workers.SocialWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.util.concurrent.TimeUnit

class WanderlyApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Supabase
        com.novahorizon.wanderly.api.SupabaseClient.init(this)

        // Required for OSMDroid to load map tiles without being blocked by servers
        Configuration.getInstance().userAgentValue = packageName

        appScope.launch {
            PreferencesStore(this@WanderlyApplication).pruneStaleCooldowns()
            setupBackgroundWorkers()
        }
    }

    private fun setupBackgroundWorkers() {
        if (BuildConfig.DEBUG) {
            Log.d("WanderlyApp", "Setting up background workers...")
        }
        val workManager = WorkManager.getInstance(this)

        val streakWorkRequest = PeriodicWorkRequestBuilder<StreakWorker>(
            15, TimeUnit.MINUTES
        ).addTag("StreakCheckWork").build()

        workManager.enqueueUniquePeriodicWork(
            "StreakCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            streakWorkRequest
        )

        val socialWorkRequest = PeriodicWorkRequestBuilder<SocialWorker>(
            15, TimeUnit.MINUTES
        ).setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
         .addTag("SocialCheckWork")
         .build()

        workManager.enqueueUniquePeriodicWork(
            "SocialCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            socialWorkRequest
        )
        
        if (BuildConfig.DEBUG) {
            Log.d("WanderlyApp", "Background workers scheduled with KEEP policy.")
        }
    }
}
