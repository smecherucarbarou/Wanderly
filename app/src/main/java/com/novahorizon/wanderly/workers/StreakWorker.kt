package com.novahorizon.wanderly.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.notifications.NotificationCheckCoordinator

class StreakWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("StreakWorker", "--- Streak Check Started ---")
        val repository = WanderlyGraph.repository(applicationContext)
        if (AuthSessionCoordinator.awaitResolvedSessionOrNull(7_000L) == null) {
            Log.w("StreakWorker", "Auth not ready after waiting. Scheduling retry.")
            return Result.retry()
        }

        NotificationCheckCoordinator.runTimedStreakCheck(
            context = applicationContext,
            repository = repository,
            source = "worker_streak"
        )
        return Result.success()
    }
}
