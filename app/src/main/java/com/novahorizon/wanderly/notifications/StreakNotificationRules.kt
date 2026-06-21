package com.novahorizon.wanderly.notifications

import android.content.Context
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.streak.DailyStreakStatus
import com.novahorizon.wanderly.streak.DailyStreakStatusEvaluator
import com.novahorizon.wanderly.util.DateUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object StreakNotificationRules {
    private const val REMINDER_START_HOUR = 10
    private const val EVENING_START_HOUR = 20

    suspend fun runTimedCheck(
        context: Context,
        repository: WanderlyRepository,
        source: String,
        stateStore: NotificationStateStore
    ) {
        if (BuildConfig.DEBUG) {
            NotificationCheckCoordinator.log("streak", source, "Suppressed in debug build.")
            return
        }

        val profile = repository.getCurrentProfile()
        if (profile == null) {
            NotificationCheckCoordinator.log("streak", source, "Skipped: no current profile.")
            return
        }

        val streakCount = profile.streak_count ?: 0
        if (streakCount <= 0) {
            NotificationCheckCoordinator.log("streak", source, "Skipped: no active streak to protect.")
            return
        }

        val todayUtc = utcDate()
        val lastMissionDate = profile.last_mission_date?.takeIf { it.isNotBlank() }
            ?: repository.getLastVisitDate().orEmpty()
        val streakStatus = DailyStreakStatusEvaluator.evaluate(
            streakCount = streakCount,
            lastMissionDate = lastMissionDate,
            today = java.time.LocalDate.parse(todayUtc)
        )

        if (streakStatus == DailyStreakStatus.HARD_LOST) {
            val lostSentDate = stateStore.getStreakLostDate()
            if (lostSentDate == todayUtc) {
                NotificationCheckCoordinator.log("streak", source, "Streak lost already announced for $todayUtc.")
                return
            }

            WanderlyNotificationManager.sendStreakLost(context)
            stateStore.setStreakLostDate(todayUtc)
            stateStore.clearStreakReminderWindows()
            NotificationCheckCoordinator.log("streak", source, "Sent streak lost alert. Last mission was $lastMissionDate.")
            return
        }

        if (streakStatus == DailyStreakStatus.ACTIVE_TODAY) {
            NotificationCheckCoordinator.log("streak", source, "Skipped: today's mission already completed ($todayUtc).")
            return
        }

        val localNow = Calendar.getInstance()
        val localDate = localDate(localNow.time)
        val localHour = localNow.get(Calendar.HOUR_OF_DAY)

        when {
            localHour in REMINDER_START_HOUR until EVENING_START_HOUR -> {
                val lastReminderDate = stateStore.getStreakReminderDate()
                if (lastReminderDate == localDate) {
                    NotificationCheckCoordinator.log("streak", source, "Reminder already sent for $localDate.")
                    return
                }

                WanderlyNotificationManager.sendDailyReminder(context, streakCount)
                stateStore.setStreakReminderDate(localDate)
                NotificationCheckCoordinator.log("streak", source, "Sent daily reminder for streak=$streakCount.")
            }

            localHour >= EVENING_START_HOUR -> {
                val lastEveningDate = stateStore.getStreakEveningDate()
                if (lastEveningDate == localDate) {
                    NotificationCheckCoordinator.log("streak", source, "Evening alert already sent for $localDate.")
                    return
                }

                WanderlyNotificationManager.sendEveningAlert(context)
                stateStore.setStreakEveningDate(localDate)
                NotificationCheckCoordinator.log("streak", source, "Sent evening alert after $EVENING_START_HOUR:00.")
            }

            else -> {
                NotificationCheckCoordinator.log(
                    "streak",
                    source,
                    "Not eligible yet. Waiting until $REMINDER_START_HOUR:00 local time."
                )
            }
        }
    }

    private fun utcDate(): String = DateUtils.formatUtcDate(Date())

    private fun localDate(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
}
