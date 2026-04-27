package com.novahorizon.wanderly.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.notifications.NotificationCheckCoordinator
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.LogRedactor
import kotlinx.coroutines.CancellationException

class StreakWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (BuildConfig.DEBUG) {
            Log.d("StreakWorker", "--- Streak Check Started ---")
        }
        val repository = WanderlyGraph.repository(applicationContext)
        if (AuthSessionCoordinator.awaitResolvedSessionOrNull(7_000L) == null) {
            if (BuildConfig.DEBUG) {
                Log.w("StreakWorker", "Auth not ready after waiting. Scheduling retry.")
            }
            return Result.retry()
        }

        return try {
            NotificationCheckCoordinator.runTimedStreakCheck(
                context = applicationContext,
                repository = repository,
                source = "worker_streak"
            )
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            CrashReporter.recordNonFatal(
                CrashEvent.STREAK_WORKER_FAILED,
                e,
                CrashKey.COMPONENT to "worker",
                CrashKey.OPERATION to "streak_check"
            )
            if (BuildConfig.DEBUG) {
                Log.e("StreakWorker", "Error in StreakWorker [${e.javaClass.simpleName}: ${LogRedactor.redact(e.message)}]")
            } else {
                Log.e("StreakWorker", "Error in StreakWorker")
            }
            Result.retry()
        }
    }
}
