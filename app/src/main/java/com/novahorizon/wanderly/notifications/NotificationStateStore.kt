package com.novahorizon.wanderly.notifications

import android.content.Context
import com.novahorizon.wanderly.data.PreferencesStore

class NotificationStateStore(context: Context) {
    private val preferencesStore = PreferencesStore(context.applicationContext)

    suspend fun getStreakReminderDate(): String? =
        preferencesStore.getNotificationCheckString(KEY_STREAK_REMINDER_DATE)

    suspend fun setStreakReminderDate(date: String) {
        preferencesStore.putNotificationCheckString(KEY_STREAK_REMINDER_DATE, date)
    }

    suspend fun getStreakEveningDate(): String? =
        preferencesStore.getNotificationCheckString(KEY_STREAK_EVENING_DATE)

    suspend fun setStreakEveningDate(date: String) {
        preferencesStore.putNotificationCheckString(KEY_STREAK_EVENING_DATE, date)
    }

    suspend fun getStreakLostDate(): String? =
        preferencesStore.getNotificationCheckString(KEY_STREAK_LOST_DATE)

    suspend fun setStreakLostDate(date: String) {
        preferencesStore.putNotificationCheckString(KEY_STREAK_LOST_DATE, date)
    }

    suspend fun clearStreakReminderWindows() {
        preferencesStore.removeNotificationCheckKeys(
            listOf(KEY_STREAK_REMINDER_DATE, KEY_STREAK_EVENING_DATE)
        )
    }

    suspend fun getOvertakenRivalId(): String? =
        preferencesStore.getNotificationCheckString(KEY_SOCIAL_TOP_OVERTAKEN)

    suspend fun setOvertakenRivalId(rivalId: String) {
        preferencesStore.putNotificationCheckString(KEY_SOCIAL_TOP_OVERTAKEN, rivalId)
    }

    suspend fun clearOvertakenRivalId() {
        preferencesStore.removeNotificationCheckKey(KEY_SOCIAL_TOP_OVERTAKEN)
    }

    suspend fun getThreatRivalId(): String? =
        preferencesStore.getNotificationCheckString(KEY_SOCIAL_TOP_THREAT)

    suspend fun setThreatRivalId(rivalId: String) {
        preferencesStore.putNotificationCheckString(KEY_SOCIAL_TOP_THREAT, rivalId)
    }

    suspend fun clearThreatRivalId() {
        preferencesStore.removeNotificationCheckKey(KEY_SOCIAL_TOP_THREAT)
    }

    suspend fun markRivalMissionIfNew(day: String, rivalId: String): Boolean {
        val key = "social.rival.$day.$rivalId"
        if (preferencesStore.getNotificationCheckBoolean(key)) {
            return false
        }

        preferencesStore.putNotificationCheckBoolean(key, true)
        return true
    }

    suspend fun hasAggregateChanged(day: String, signature: String): Boolean {
        val savedDay = preferencesStore.getNotificationCheckString(KEY_SOCIAL_AGGREGATE_DATE)
        val savedSignature = preferencesStore.getNotificationCheckString(KEY_SOCIAL_AGGREGATE_SIGNATURE)
        return savedDay != day || savedSignature != signature
    }

    suspend fun persistAggregateState(day: String, signature: String) {
        preferencesStore.putNotificationCheckString(KEY_SOCIAL_AGGREGATE_DATE, day)
        preferencesStore.putNotificationCheckString(KEY_SOCIAL_AGGREGATE_SIGNATURE, signature)
    }

    suspend fun clearTopState() {
        preferencesStore.removeNotificationCheckKeys(
            listOf(KEY_SOCIAL_TOP_OVERTAKEN, KEY_SOCIAL_TOP_THREAT)
        )
    }

    suspend fun clearAll() {
        preferencesStore.clearNotificationCheckState()
    }

    suspend fun clearForType(type: WanderlyNotificationManager.NotificationType) {
        when (type) {
            WanderlyNotificationManager.NotificationType.DAILY_REMINDER -> {
                preferencesStore.removeNotificationCheckKey(KEY_STREAK_REMINDER_DATE)
            }

            WanderlyNotificationManager.NotificationType.EVENING_ALERT -> {
                preferencesStore.removeNotificationCheckKey(KEY_STREAK_EVENING_DATE)
            }

            WanderlyNotificationManager.NotificationType.STREAK_LOST -> {
                preferencesStore.removeNotificationCheckKey(KEY_STREAK_LOST_DATE)
            }

            WanderlyNotificationManager.NotificationType.MILESTONE -> {
                // No evaluator-side state for these notifications yet.
            }

            WanderlyNotificationManager.NotificationType.RIVAL_ACTIVITY -> {
                preferencesStore.getNotificationCheckKeys()
                    .filter { it.startsWith("social.rival.") }
                    .forEach { key ->
                        preferencesStore.removeNotificationCheckKey(key)
                    }
            }

            WanderlyNotificationManager.NotificationType.AGGREGATED_RIVAL_ACTIVITY -> {
                preferencesStore.removeNotificationCheckKeys(
                    listOf(KEY_SOCIAL_AGGREGATE_DATE, KEY_SOCIAL_AGGREGATE_SIGNATURE)
                )
            }

            WanderlyNotificationManager.NotificationType.OVERTAKEN -> {
                preferencesStore.removeNotificationCheckKey(KEY_SOCIAL_TOP_OVERTAKEN)
            }

            WanderlyNotificationManager.NotificationType.FIGHT_FOR_FIRST -> {
                preferencesStore.removeNotificationCheckKey(KEY_SOCIAL_TOP_THREAT)
            }
        }
    }

    private companion object {
        private const val KEY_STREAK_REMINDER_DATE = "streak.reminder.date"
        private const val KEY_STREAK_EVENING_DATE = "streak.evening.date"
        private const val KEY_STREAK_LOST_DATE = "streak.lost.date"
        private const val KEY_SOCIAL_TOP_OVERTAKEN = "social.top_overtaken"
        private const val KEY_SOCIAL_TOP_THREAT = "social.top_threat"
        private const val KEY_SOCIAL_AGGREGATE_DATE = "social.aggregate.date"
        private const val KEY_SOCIAL_AGGREGATE_SIGNATURE = "social.aggregate.signature"
    }
}
