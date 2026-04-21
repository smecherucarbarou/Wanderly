package com.novahorizon.wanderly

import android.app.Application
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
            setupBackgroundWorkers()
        }
    }

    private fun setupBackgroundWorkers() {
        Log.d("WanderlyApp", "Setting up background workers...")
        val workManager = WorkManager.getInstance(this)
        
        // 1. Streak Worker - Using KEEP to preserve the 15-minute interval across app restarts
        val streakWorkRequest = PeriodicWorkRequestBuilder<StreakWorker>(
            15, TimeUnit.MINUTES 
        ).addTag("StreakCheckWork").build()

        workManager.enqueueUniquePeriodicWork(
            "StreakCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            streakWorkRequest
        )

        // 2. Social Worker (Rivals/Rankings)
        // FIX BUG 2: Add backoff policy for retry on Auth race condition
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
        
        Log.d("WanderlyApp", "Background workers scheduled with KEEP policy.")
    }
}
