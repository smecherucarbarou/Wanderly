package com.novahorizon.wanderly.notifications

import com.novahorizon.wanderly.observability.AppLogger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.MainActivity
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.PreferencesStore
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.util.Clock
import com.novahorizon.wanderly.util.SystemClock

object WanderlyNotificationManager {
    enum class NotificationType {
        DAILY_REMINDER,
        EVENING_ALERT,
        MILESTONE,
        STREAK_LOST,
        RIVAL_ACTIVITY,
        AGGREGATED_RIVAL_ACTIVITY,
        OVERTAKEN,
        FIGHT_FOR_FIRST
    }

    private const val CHANNEL_ID = "wanderly_alerts_v3"
    private const val CHANNEL_NAME = "Wanderly Hive Alerts"
    private const val LOG_TAG = "WanderlyNotif"
    private const val DEFAULT_COOLDOWN_MS = 30 * 60 * 1000L
    private const val DAILY_REMINDER_COOLDOWN_MS = 2 * 60 * 60 * 1000L
    private const val EVENING_ALERT_COOLDOWN_MS = 90 * 60 * 1000L
    private const val MILESTONE_COOLDOWN_MS = 12 * 60 * 60 * 1000L
    private const val STREAK_LOST_COOLDOWN_MS = 12 * 60 * 60 * 1000L
    private const val RIVAL_ACTIVITY_COOLDOWN_MS = 20 * 60 * 1000L
    private const val AGGREGATED_RIVAL_COOLDOWN_MS = 25 * 60 * 1000L
    private const val OVERTAKEN_COOLDOWN_MS = 45 * 60 * 1000L
    private const val FIGHT_FOR_FIRST_COOLDOWN_MS = 20 * 60 * 1000L
    internal var clock: Clock = SystemClock
    @Volatile
    private var permissionWarningLogged = false
    @Volatile
    private var systemDisabledWarningLogged = false

    private suspend fun isNotificationCooldownActive(context: Context, key: String): Boolean {
        val preferencesStore = PreferencesStore(context)
        val lastSent = preferencesStore.getNotificationCooldown(key)
        val now = clock.nowMillis()
        val cooldownMs = cooldownForKey(key)

        if (now - lastSent < cooldownMs) {
            logDebug("Cooldown active for $key (${cooldownMs / 1000}s). Skipping notification.")
            return true
        }

        preferencesStore.setNotificationCooldown(key, now)
        return false
    }

    suspend fun clearNotificationCooldowns(context: Context) {
        PreferencesStore(context).clearNotificationCooldowns()
        logDebug("Notification cooldown cache cleared.")
    }

    suspend fun clearNotificationCooldown(context: Context, type: NotificationType) {
        val preferencesStore = PreferencesStore(context)
        for (key in preferencesStore.getNotificationCooldownKeys()) {
            if (matchesType(key, type)) {
                preferencesStore.removeNotificationCooldown(key)
            }
        }
        logDebug("Notification cooldown cleared for $type.")
    }

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Real-time updates about your rivals and streaks"
            enableLights(true)
            lightColor = Color.YELLOW
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    suspend fun showNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = 1001,
        dedupKey: String? = null,
        bypassCooldown: Boolean = false
    ): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            logWarnOnce(NotificationWarning.Permission, "POST_NOTIFICATIONS permission not granted")
            return false
        }

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            logWarnOnce(NotificationWarning.SystemDisabled, "Notifications disabled by user in system settings")
            return false
        }

        logDebug("Attempting notification id=$notificationId key=${dedupKey?.substringBefore('_') ?: "none"}")

        if (dedupKey != null && !bypassCooldown && isNotificationCooldownActive(context, dedupKey)) {
            return false
        }

        createNotificationChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            notificationManager.notify(notificationId, builder.build())
            logDebug("SUCCESS: Notification $notificationId sent.")
            return true
        } catch (e: Exception) {
            logError("FAILED to send notification: ${e.message}")
            return false
        }
    }

    suspend fun sendDailyReminder(context: Context, streakDays: Int, force: Boolean = false): Boolean {
        return showNotification(
            context,
            "Do not break the streak",
            "You have a $streakDays-day streak. Complete today's mission!",
            2001,
            "daily_reminder",
            force
        )
    }

    suspend fun sendEveningAlert(context: Context, force: Boolean = false): Boolean {
        return showNotification(
            context,
            "Critical mission",
            "Your streak expires at midnight. Got time for one last run?",
            2002,
            "evening_alert",
            force
        )
    }

    suspend fun sendMilestoneCelebration(context: Context, streakDays: Int, force: Boolean = false): Boolean {
        return showNotification(
            context,
            "Milestone reached",
            "$streakDays days. Gemini is impressed.",
            2003,
            "milestone_$streakDays",
            force
        )
    }

    suspend fun sendStreakLost(context: Context, force: Boolean = false): Boolean {
        return showNotification(
            context,
            "Streak lost",
            "Time to rebuild from zero.",
            2004,
            "streak_lost",
            force
        )
    }

    suspend fun sendRivalActivity(context: Context, name: String, force: Boolean = false): Boolean {
        val id = 3000 + (name.hashCode() and 0x0FFF)
        return showNotification(
            context,
            "Rival alert",
            "$name just finished a mission. Keep up!",
            id,
            "rival_$name",
            force
        )
    }

    suspend fun sendAggregatedRivalActivity(context: Context, count: Int, force: Boolean = false): Boolean {
        return showNotification(
            context,
            "Hive activity",
            "$count rivals completed missions today. Get moving!",
            3002,
            "aggregated_rivals",
            force
        )
    }

    suspend fun sendOvertakenAlert(context: Context, name: String, force: Boolean = false): Boolean {
        return showNotification(
            context,
            "Overtaken",
            "$name has overtaken you in the hive rankings.",
            3003,
            "overtaken",
            force
        )
    }

    suspend fun sendFightForFirst(context: Context, name: String, force: Boolean = false): Boolean {
        return showNotification(
            context,
            "Battle for first",
            "$name is right behind you. Do not let them win!",
            3004,
            "fight_for_first",
            force
        )
    }

    private fun cooldownForKey(key: String): Long = when {
        key == "daily_reminder" -> DAILY_REMINDER_COOLDOWN_MS
        key == "evening_alert" -> EVENING_ALERT_COOLDOWN_MS
        key.startsWith("milestone_") -> MILESTONE_COOLDOWN_MS
        key == "streak_lost" -> STREAK_LOST_COOLDOWN_MS
        key.startsWith("rival_") -> RIVAL_ACTIVITY_COOLDOWN_MS
        key == "aggregated_rivals" -> AGGREGATED_RIVAL_COOLDOWN_MS
        key == "overtaken" -> OVERTAKEN_COOLDOWN_MS
        key == "fight_for_first" -> FIGHT_FOR_FIRST_COOLDOWN_MS
        else -> DEFAULT_COOLDOWN_MS
    }

    private fun matchesType(key: String, type: NotificationType): Boolean = when (type) {
        NotificationType.DAILY_REMINDER -> key == "daily_reminder"
        NotificationType.EVENING_ALERT -> key == "evening_alert"
        NotificationType.MILESTONE -> key.startsWith("milestone_")
        NotificationType.STREAK_LOST -> key == "streak_lost"
        NotificationType.RIVAL_ACTIVITY -> key.startsWith("rival_")
        NotificationType.AGGREGATED_RIVAL_ACTIVITY -> key == "aggregated_rivals"
        NotificationType.OVERTAKEN -> key == "overtaken"
        NotificationType.FIGHT_FOR_FIRST -> key == "fight_for_first"
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d(LOG_TAG, LogRedactor.redact(message))
        }
    }

    private fun logWarn(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.w(LOG_TAG, LogRedactor.redact(message))
        }
    }

    private fun logWarnOnce(type: NotificationWarning, message: String) {
        when (type) {
            NotificationWarning.Permission -> {
                if (permissionWarningLogged) return
                permissionWarningLogged = true
            }
            NotificationWarning.SystemDisabled -> {
                if (systemDisabledWarningLogged) return
                systemDisabledWarningLogged = true
            }
        }
        logWarn(message)
    }

    private fun logError(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.e(LOG_TAG, LogRedactor.redact(message))
        }
    }

    private enum class NotificationWarning {
        Permission,
        SystemDisabled
    }
}
