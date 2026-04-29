package com.novahorizon.wanderly

import com.novahorizon.wanderly.observability.AppLogger

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.novahorizon.wanderly.data.PreferencesStore
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.StrictModeInitializer
import com.novahorizon.wanderly.workers.StreakWorker
import com.novahorizon.wanderly.workers.SocialWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.util.concurrent.TimeUnit
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class WanderlyApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        StrictModeInitializer.enableForDebugBuild()
        CrashReporter.initialize(this, BuildConfig.CRASH_REPORTING_CONFIGURED)
        
        // Initialize Supabase
        com.novahorizon.wanderly.api.SupabaseClient.init(this)

        initOsmdroidAsync()

        appScope.launch {
            PreferencesStore(this@WanderlyApplication).pruneStaleCooldowns()
            setupBackgroundWorkers()
        }
    }

    private fun initOsmdroidAsync() {
        appScope.launch(Dispatchers.IO) {
            Configuration.getInstance().load(
                applicationContext,
                applicationContext.getSharedPreferences("osmdroid", MODE_PRIVATE)
            )
            Configuration.getInstance().userAgentValue = packageName
        }
    }

    private fun setupBackgroundWorkers() {
        if (BuildConfig.DEBUG) {
            AppLogger.d("WanderlyApp", "Setting up background workers...")
        }
        val workManager = WorkManager.getInstance(this)
        val backgroundWorkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val streakWorkRequest = PeriodicWorkRequestBuilder<StreakWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(backgroundWorkConstraints)
            .addTag("StreakCheckWork")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "StreakCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            streakWorkRequest
        )

        val socialWorkRequest = PeriodicWorkRequestBuilder<SocialWorker>(
            15, TimeUnit.MINUTES
        ).setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(backgroundWorkConstraints)
            .addTag("SocialCheckWork")
            .build()

        workManager.enqueueUniquePeriodicWork(
            "SocialCheckWork",
            ExistingPeriodicWorkPolicy.KEEP,
            socialWorkRequest
        )
        
        if (BuildConfig.DEBUG) {
            AppLogger.d("WanderlyApp", "Background workers scheduled with KEEP policy.")
        }
    }
}
