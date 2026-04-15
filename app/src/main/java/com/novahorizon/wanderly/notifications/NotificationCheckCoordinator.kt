package com.novahorizon.wanderly.notifications

import android.content.Context
import android.util.Log
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object NotificationCheckCoordinator {
    private const val PREFS_NAME = "notification_check_state"
    private const val LOG_TAG = "NotifCheck"

    private const val KEY_STREAK_REMINDER_DATE = "streak.reminder.date"
    private const val KEY_STREAK_EVENING_DATE = "streak.evening.date"
    private const val KEY_STREAK_LOST_DATE = "streak.lost.date"
    private const val KEY_SOCIAL_TOP_OVERTAKEN = "social.top_overtaken"
    private const val KEY_SOCIAL_TOP_THREAT = "social.top_threat"
    private const val KEY_SOCIAL_AGGREGATE_DATE = "social.aggregate.date"
    private const val KEY_SOCIAL_AGGREGATE_SIGNATURE = "social.aggregate.signature"

    private const val REMINDER_START_HOUR = 10
    private const val EVENING_START_HOUR = 20

    suspend fun runTimedStreakCheck(
        context: Context,
        repository: WanderlyRepository,
        source: String
    ) {
        val profile = repository.getCurrentProfile()
        if (profile == null) {
            log("streak", source, "Skipped: no current profile.")
            return
        }

        val streakCount = profile.streak_count ?: 0
        if (streakCount <= 0) {
            log("streak", source, "Skipped: no active streak to protect.")
            return
        }

        val todayUtc = utcDate()
        val lastMissionDate = profile.last_mission_date?.takeIf { it.isNotBlank() }
            ?: repository.getLastVisitDate().orEmpty()
        val yesterdayUtc = utcDateOffset(-1)

        if (lastMissionDate.isNotBlank() && lastMissionDate != todayUtc && lastMissionDate != yesterdayUtc) {
            val prefs = prefs(context)
            val lostSentDate = prefs.getString(KEY_STREAK_LOST_DATE, null)
            if (lostSentDate == todayUtc) {
                log("streak", source, "Streak lost already announced for $todayUtc.")
                return
            }

            WanderlyNotificationManager.sendStreakLost(context)
            prefs.edit()
                .putString(KEY_STREAK_LOST_DATE, todayUtc)
                .remove(KEY_STREAK_REMINDER_DATE)
                .remove(KEY_STREAK_EVENING_DATE)
                .apply()
            log("streak", source, "Sent streak lost alert. Last mission was $lastMissionDate.")
            return
        }

        if (lastMissionDate == todayUtc) {
            log("streak", source, "Skipped: today's mission already completed ($todayUtc).")
            return
        }

        val localNow = Calendar.getInstance()
        val localDate = localDate(localNow.time)
        val localHour = localNow.get(Calendar.HOUR_OF_DAY)
        val prefs = prefs(context)

        when {
            localHour in REMINDER_START_HOUR until EVENING_START_HOUR -> {
                val lastReminderDate = prefs.getString(KEY_STREAK_REMINDER_DATE, null)
                if (lastReminderDate == localDate) {
                    log("streak", source, "Reminder already sent for $localDate.")
                    return
                }

                WanderlyNotificationManager.sendDailyReminder(context, streakCount)
                prefs.edit().putString(KEY_STREAK_REMINDER_DATE, localDate).apply()
                log("streak", source, "Sent daily reminder for streak=$streakCount.")
            }

            localHour >= EVENING_START_HOUR -> {
                val lastEveningDate = prefs.getString(KEY_STREAK_EVENING_DATE, null)
                if (lastEveningDate == localDate) {
                    log("streak", source, "Evening alert already sent for $localDate.")
                    return
                }

                WanderlyNotificationManager.sendEveningAlert(context)
                prefs.edit().putString(KEY_STREAK_EVENING_DATE, localDate).apply()
                log("streak", source, "Sent evening alert after $EVENING_START_HOUR:00.")
            }

            else -> {
                log(
                    "streak",
                    source,
                    "Not eligible yet. Waiting until $REMINDER_START_HOUR:00 local time."
                )
            }
        }
    }

    suspend fun runSocialFallbackCheck(
        context: Context,
        repository: WanderlyRepository,
        source: String
    ) {
        val currentProfile = repository.getCurrentProfile()
        if (currentProfile == null) {
            log("social", source, "Skipped: no current profile.")
            return
        }

        val friends = repository.getFriends()
        if (friends.isEmpty()) {
            clearTopState(context)
            log("social", source, "Skipped: no friends to track.")
            return
        }

        val today = utcDate()
        val rivalsFinishedToday = friends.filter { it.last_mission_date == today }
        val userHoney = currentProfile.honey ?: 0

        when (rivalsFinishedToday.size) {
            0 -> log("social", source, "No rival missions completed today.")
            1 -> {
                val rival = rivalsFinishedToday.first()
                if (markRivalMissionIfNew(context, today, rival.id)) {
                    WanderlyNotificationManager.sendRivalActivity(
                        context,
                        rival.username ?: "A rival"
                    )
                    log("social", source, "Sent single-rival activity for ${rival.id}.")
                } else {
                    log("social", source, "Single-rival activity already handled for ${rival.id}.")
                }
            }

            else -> {
                log(
                    "social",
                    source,
                    "Grouped rival activity is temporarily disabled (${rivalsFinishedToday.size} rivals today)."
                )
            }
        }

        val topRival = friends
            .filter { (it.honey ?: 0) > userHoney }
            .maxByOrNull { it.honey ?: 0 }
        handleOvertakenState(context, topRival, source)

        if (topRival != null) {
            clearThreatState(context)
            log("social", source, "Skipping fight-for-first because a rival is already ahead.")
            return
        }

        val topThreat = friends
            .filter {
                val theirHoney = it.honey ?: 0
                theirHoney < userHoney && theirHoney >= (userHoney * 0.90).toInt()
            }
            .maxByOrNull { it.honey ?: 0 }
        handleThreatState(context, topThreat, source)
    }

    suspend fun handleRealtimeProfileUpdate(
        context: Context,
        repository: WanderlyRepository,
        currentProfile: Profile,
        updatedProfile: Profile
    ) {
        val today = utcDate()
        val source = "service_realtime"
        val currentHoney = currentProfile.honey ?: 0
        val updatedHoney = updatedProfile.honey ?: 0

        if (updatedProfile.last_mission_date == today &&
            markRivalMissionIfNew(context, today, updatedProfile.id)
        ) {
            WanderlyNotificationManager.sendRivalActivity(
                context,
                updatedProfile.username ?: "A rival"
            )
            log("social", source, "Realtime rival mission from ${updatedProfile.id}.")
        }

        val friends = repository.getFriends()
        if (friends.isEmpty()) {
            clearTopState(context)
            log("social", source, "Realtime update ignored because there are no friends.")
            return
        }

        val topRival = friends
            .filter { (it.honey ?: 0) > currentHoney }
            .maxByOrNull { it.honey ?: 0 }

        if (topRival != null) {
            clearThreatState(context)
            handleOvertakenState(context, topRival, source)
            log(
                "social",
                source,
                "Skipping fight-for-first because ${topRival.id} is currently ahead."
            )
            return
        }

        val topThreat = friends
            .filter {
                val theirHoney = it.honey ?: 0
                theirHoney < currentHoney && theirHoney >= (currentHoney * 0.90).toInt()
            }
            .maxByOrNull { it.honey ?: 0 }

        if (topThreat != null && (updatedHoney < currentHoney && updatedHoney >= (currentHoney * 0.90).toInt())) {
            handleThreatState(context, topThreat, source)
            return
        }

        log(
            "social",
            source,
            "Realtime update from ${updatedProfile.id} did not change the leading social state."
        )
    }

    fun log(category: String, source: String, message: String) {
        Log.d(LOG_TAG, "[$category][$source] $message")
    }

    fun clearCheckState(context: Context) {
        prefs(context).edit().clear().apply()
        log("debug", "admin_panel", "Notification check state cleared.")
    }

    fun clearCheckStateForType(
        context: Context,
        type: WanderlyNotificationManager.NotificationType
    ) {
        val editor = prefs(context).edit()
        when (type) {
            WanderlyNotificationManager.NotificationType.DAILY_REMINDER -> {
                editor.remove(KEY_STREAK_REMINDER_DATE)
            }

            WanderlyNotificationManager.NotificationType.EVENING_ALERT -> {
                editor.remove(KEY_STREAK_EVENING_DATE)
            }

            WanderlyNotificationManager.NotificationType.STREAK_LOST -> {
                editor.remove(KEY_STREAK_LOST_DATE)
            }

            WanderlyNotificationManager.NotificationType.MILESTONE -> {
                // No evaluator-side state for these notifications yet.
            }

            WanderlyNotificationManager.NotificationType.RIVAL_ACTIVITY -> {
                prefs(context).all.keys
                    .filter { it.startsWith("social.rival.") }
                    .forEach { editor.remove(it) }
            }

            WanderlyNotificationManager.NotificationType.AGGREGATED_RIVAL_ACTIVITY -> {
                editor.remove(KEY_SOCIAL_AGGREGATE_DATE)
                editor.remove(KEY_SOCIAL_AGGREGATE_SIGNATURE)
            }

            WanderlyNotificationManager.NotificationType.OVERTAKEN -> {
                editor.remove(KEY_SOCIAL_TOP_OVERTAKEN)
            }

            WanderlyNotificationManager.NotificationType.FIGHT_FOR_FIRST -> {
                editor.remove(KEY_SOCIAL_TOP_THREAT)
            }
        }
        editor.apply()
        log("debug", "admin_panel", "Notification check state cleared for $type.")
    }

    private fun handleOvertakenState(context: Context, topRival: Profile?, source: String) {
        val prefs = prefs(context)
        val previousId = prefs.getString(KEY_SOCIAL_TOP_OVERTAKEN, null)

        if (topRival == null) {
            if (previousId != null) {
                prefs.edit().remove(KEY_SOCIAL_TOP_OVERTAKEN).apply()
            }
            log("social", source, "No rival currently ahead.")
            return
        }

        if (topRival.id == previousId) {
            log("social", source, "Top overtaken rival unchanged: ${topRival.id}.")
            return
        }

        WanderlyNotificationManager.sendOvertakenAlert(
            context,
            topRival.username ?: "Someone"
        )
        prefs.edit().putString(KEY_SOCIAL_TOP_OVERTAKEN, topRival.id).apply()
        log("social", source, "Sent overtaken alert for ${topRival.id}.")
    }

    private fun handleThreatState(context: Context, topThreat: Profile?, source: String) {
        val prefs = prefs(context)
        val previousId = prefs.getString(KEY_SOCIAL_TOP_THREAT, null)

        if (topThreat == null) {
            if (previousId != null) {
                prefs.edit().remove(KEY_SOCIAL_TOP_THREAT).apply()
            }
            log("social", source, "No near-overtake threat right now.")
            return
        }

        if (topThreat.id == previousId) {
            log("social", source, "Top threat unchanged: ${topThreat.id}.")
            return
        }

        WanderlyNotificationManager.sendFightForFirst(
            context,
            topThreat.username ?: "Someone"
        )
        prefs.edit().putString(KEY_SOCIAL_TOP_THREAT, topThreat.id).apply()
        log("social", source, "Sent fight-for-first alert for ${topThreat.id}.")
    }

    private fun markRivalMissionIfNew(context: Context, day: String, rivalId: String): Boolean {
        val key = "social.rival.$day.$rivalId"
        val prefs = prefs(context)
        if (prefs.getBoolean(key, false)) {
            return false
        }

        prefs.edit().putBoolean(key, true).apply()
        return true
    }

    private fun markAggregateIfChanged(context: Context, day: String, signature: String): Boolean {
        val prefs = prefs(context)
        val savedDay = prefs.getString(KEY_SOCIAL_AGGREGATE_DATE, null)
        val savedSignature = prefs.getString(KEY_SOCIAL_AGGREGATE_SIGNATURE, null)
        if (savedDay == day && savedSignature == signature) {
            return false
        }

        prefs.edit()
            .putString(KEY_SOCIAL_AGGREGATE_DATE, day)
            .putString(KEY_SOCIAL_AGGREGATE_SIGNATURE, signature)
            .apply()
        return true
    }

    private fun clearTopState(context: Context) {
        prefs(context).edit()
            .remove(KEY_SOCIAL_TOP_OVERTAKEN)
            .remove(KEY_SOCIAL_TOP_THREAT)
            .apply()
    }

    private fun clearThreatState(context: Context) {
        prefs(context).edit().remove(KEY_SOCIAL_TOP_THREAT).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun utcDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private fun utcDateOffset(days: Int): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        add(Calendar.DAY_OF_YEAR, days)
    }.time)

    private fun localDate(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
}
