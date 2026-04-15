/* FIXES APPLIED: BUG A — see inline comments */
package com.novahorizon.wanderly.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    private const val COOLDOWN_MS = 30 * 60 * 1000L // 30 minutes

    /**
     * Checks if a notification of this type has been sent recently.
     * Returns true if we should skip sending (cooldown active).
     */
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

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
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
        dedupKey: String? = null
    ) {
        // BUG 11 FIXED: Android 13+ Notification Permission Guard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                Log.w(LOG_TAG, "Notifications are disabled. Cannot show alert.")
                return
            }
        }

        // BUG 8 FIXED: Shared deduplication cooldown logic
        if (dedupKey != null && isNotificationCooldownActive(context, dedupKey)) {
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
            .setSmallIcon(R.drawable.ic_notification) // BUG 12 FIXED: Use white-on-transparent vector
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

    fun sendDailyReminder(context: Context, streakDays: Int) {
        showNotification(context, "Don't break the streak! ⚡", "You have a $streakDays-day streak. Complete today's mission!", 2001, "daily_reminder")
    }

    fun sendEveningAlert(context: Context) {
        showNotification(context, "Critical mission! ⚠️", "Your streak expires at midnight. Got time for one last run?", 2002, "evening_alert")
    }

    fun sendMilestoneCelebration(context: Context, streakDays: Int) =
        showNotification(context, "🏆 Milestone!", "$streakDays days! Gemini is impressed.", 2003, "milestone_$streakDays")

    fun sendStreakLost(context: Context) =
        showNotification(context, "🙄 Streak Lost", "Time to rebuild from zero.", 2004, "streak_lost")

    fun sendRivalActivity(context: Context, name: String) {
        // BUG A FIXED: Generate a stable, unique ID per rival to prevent collisions
        val id = 3000 + (name.hashCode() and 0x0FFF)
        showNotification(context, "🏃 Rival Alert!", "$name just finished a mission. Keep up!", id, "rival_$name")
    }

    fun sendAggregatedRivalActivity(context: Context, count: Int) =
        showNotification(context, "🐝 Hive Activity!", "$count rivals completed missions today! Get moving!", 3002, "aggregated_rivals")

    fun sendOvertakenAlert(context: Context, name: String) =
        showNotification(context, "📉 Overtaken!", "$name has overtaken you in the hive rankings!", 3003, "overtaken")

    fun sendFightForFirst(context: Context, name: String) =
        showNotification(context, "🥊 Battle for 1st!", "$name is right behind you. Don't let them win!", 3004, "fight_for_first")
}
