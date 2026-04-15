package com.novahorizon.wanderly.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.data.WanderlyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.novahorizon.wanderly.notifications.NotificationCheckCoordinator

class SocialWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pid = android.os.Process.myPid()
        Log.d("SocialWorker", "--- Social Polling Started (PID: $pid) ---")
        val repository = WanderlyRepository(applicationContext)

        try {
            if (AuthSessionCoordinator.awaitResolvedSessionOrNull(7_000L) == null) {
                Log.w("SocialWorker", "Auth not ready after waiting. Scheduling retry.")
                return@withContext Result.retry()
            }
            NotificationCheckCoordinator.runSocialFallbackCheck(
                context = applicationContext,
                repository = repository,
                source = "worker_social"
            )
            Log.d("SocialWorker", "--- Social Polling Finished ---")
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("SocialWorker", "Error in SocialWorker", e)
            Result.retry()
        }
    }
}
