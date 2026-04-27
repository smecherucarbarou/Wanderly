package com.novahorizon.wanderly.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.LogRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.novahorizon.wanderly.notifications.NotificationCheckCoordinator

class SocialWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pid = android.os.Process.myPid()
        if (BuildConfig.DEBUG) {
            Log.d("SocialWorker", "--- Social Polling Started (PID: $pid) ---")
        }
        val repository = WanderlyGraph.repository(applicationContext)

        try {
            if (AuthSessionCoordinator.awaitResolvedSessionOrNull(7_000L) == null) {
                if (BuildConfig.DEBUG) {
                    Log.w("SocialWorker", "Auth not ready after waiting. Scheduling retry.")
                }
                return@withContext Result.retry()
            }
            NotificationCheckCoordinator.runSocialFallbackCheck(
                context = applicationContext,
                repository = repository,
                source = "worker_social"
            )
            if (BuildConfig.DEBUG) {
                Log.d("SocialWorker", "--- Social Polling Finished ---")
            }
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            CrashReporter.recordNonFatal(
                CrashEvent.SOCIAL_WORKER_FAILED,
                e,
                CrashKey.COMPONENT to "worker",
                CrashKey.OPERATION to "social_poll"
            )
            if (BuildConfig.DEBUG) {
                Log.e("SocialWorker", "Error in SocialWorker [${e.javaClass.simpleName}: ${LogRedactor.redact(e.message)}]")
            } else {
                Log.e("SocialWorker", "Error in SocialWorker")
            }
            Result.retry()
        }
    }
}
