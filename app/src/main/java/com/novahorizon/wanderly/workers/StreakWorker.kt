package com.novahorizon.wanderly.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class StreakWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("StreakWorker", "--- Streak Check Started ---")
        val repository = WanderlyRepository(applicationContext)
        if (AuthSessionCoordinator.awaitResolvedSessionOrNull(7_000L) == null) {
            Log.w("StreakWorker", "Auth not ready after waiting. Scheduling retry.")
            return Result.retry()
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val today = sdf.format(calendar.time)
        val lastVisit = repository.getLastVisitDate() ?: ""

        Log.d("StreakWorker", "Today (UTC): $today, Last Visit: $lastVisit")

        if (lastVisit != today) {
            val localHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val profile = repository.getCurrentProfile()
            val streakCount = profile?.streak_count ?: 0

            when {
                localHour in 0..9 -> {
                    if (streakCount > 0) {
                        WanderlyNotificationManager.sendDailyReminder(applicationContext, streakCount)
                    }
                }

                localHour in 10..18 -> {
                    WanderlyNotificationManager.sendDailyReminder(applicationContext, streakCount)
                }

                localHour > 18 -> {
                    WanderlyNotificationManager.sendEveningAlert(applicationContext)
                }
            }
        }

        return Result.success()
    }
}
