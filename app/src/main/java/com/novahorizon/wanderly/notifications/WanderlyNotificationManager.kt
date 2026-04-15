package com.novahorizon.wanderly.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.novahorizon.wanderly.MainActivity
import com.novahorizon.wanderly.R

object WanderlyNotificationManager {
    private const val CHANNEL_ID = "wanderly_alerts_v3"
    private const val CHANNEL_NAME = "Wanderly Hive Alerts"
    private const val LOG_TAG = "WanderlyNotif"
    private const val PREFS_NAME = "notif_dedup"
    private const val COOLDOWN_MS = 30 * 60 * 1000L

    private fun isNotificationCooldownActive(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSent = prefs.getLong(key, 0L)
        val now = System.currentTimeMillis()

        if (now - lastSent < COOLDOWN_MS) {
            Log.d(LOG_TAG, "Cooldown active for $key. Skipping notification.")
            return true
        }

        prefs.edit().putLong(key, now).apply()
        return false
    }

    fun clearNotificationCooldowns(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    fun showNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = 1001,
        dedupKey: String? = null,
        bypassCooldown: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled()
        ) {
            Log.w(LOG_TAG, "Notifications are disabled. Cannot show alert.")
            return
        }

        if (dedupKey != null && !bypassCooldown && isNotificationCooldownActive(context, dedupKey)) {
            return
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
            Log.d(LOG_TAG, "SUCCESS: Notification $notificationId sent.")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "FAILED to send notification: ${e.message}")
        }
    }

    fun sendDailyReminder(context: Context, streakDays: Int, force: Boolean = false) {
        showNotification(
            context,
            "Do not break the streak",
            "You have a $streakDays-day streak. Complete today's mission!",
            2001,
            "daily_reminder",
            force
        )
    }

    fun sendEveningAlert(context: Context, force: Boolean = false) {
        showNotification(
            context,
            "Critical mission",
            "Your streak expires at midnight. Got time for one last run?",
            2002,
            "evening_alert",
            force
        )
    }

    fun sendMilestoneCelebration(context: Context, streakDays: Int, force: Boolean = false) {
        showNotification(
            context,
            "Milestone reached",
            "$streakDays days. Gemini is impressed.",
            2003,
            "milestone_$streakDays",
            force
        )
    }

    fun sendStreakLost(context: Context, force: Boolean = false) {
        showNotification(
            context,
            "Streak lost",
            "Time to rebuild from zero.",
            2004,
            "streak_lost",
            force
        )
    }

    fun sendRivalActivity(context: Context, name: String, force: Boolean = false) {
        val id = 3000 + (name.hashCode() and 0x0FFF)
        showNotification(
            context,
            "Rival alert",
            "$name just finished a mission. Keep up!",
            id,
            "rival_$name",
            force
        )
    }

    fun sendAggregatedRivalActivity(context: Context, count: Int, force: Boolean = false) {
        showNotification(
            context,
            "Hive activity",
            "$count rivals completed missions today. Get moving!",
            3002,
            "aggregated_rivals",
            force
        )
    }

    fun sendOvertakenAlert(context: Context, name: String, force: Boolean = false) {
        showNotification(
            context,
            "Overtaken",
            "$name has overtaken you in the hive rankings.",
            3003,
            "overtaken",
            force
        )
    }

    fun sendFightForFirst(context: Context, name: String, force: Boolean = false) {
        showNotification(
            context,
            "Battle for first",
            "$name is right behind you. Do not let them win!",
            3004,
            "fight_for_first",
            force
        )
    }
}
