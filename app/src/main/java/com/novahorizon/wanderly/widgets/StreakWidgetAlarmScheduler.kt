package com.novahorizon.wanderly.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

enum class AlarmDeliveryMode {
    EXACT_ALLOW_WHILE_IDLE,
    INEXACT_ALLOW_WHILE_IDLE
}

data class AlarmSchedulePlan(
    val triggerAtMillis: Long,
    val useExactAlarm: Boolean,
    val deliveryMode: AlarmDeliveryMode
)

object StreakWidgetAlarmScheduler {
    const val REFRESH_INTERVAL_MILLIS = 15_000L

    fun resolveSchedulePlan(
        nowMillis: Long,
        sdkInt: Int,
        canScheduleExactAlarms: Boolean
    ): AlarmSchedulePlan {
        val triggerAtMillis = nowMillis + REFRESH_INTERVAL_MILLIS
        val useExactAlarm = sdkInt < Build.VERSION_CODES.S || canScheduleExactAlarms
        val deliveryMode = if (useExactAlarm) {
            AlarmDeliveryMode.EXACT_ALLOW_WHILE_IDLE
        } else {
            AlarmDeliveryMode.INEXACT_ALLOW_WHILE_IDLE
        }

        return AlarmSchedulePlan(
            triggerAtMillis = triggerAtMillis,
            useExactAlarm = useExactAlarm,
            deliveryMode = deliveryMode
        )
    }

    fun scheduleNext(
        context: Context,
        alarmManager: AlarmManager,
        pendingIntent: PendingIntent,
        nowMillis: Long = System.currentTimeMillis(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ) {
        val canScheduleExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
            return
        }
        val plan = resolveSchedulePlan(
            nowMillis = nowMillis,
            sdkInt = sdkInt,
            canScheduleExactAlarms = canScheduleExactAlarms
        )

        when (plan.deliveryMode) {
            AlarmDeliveryMode.EXACT_ALLOW_WHILE_IDLE -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    plan.triggerAtMillis,
                    pendingIntent
                )
            }

            AlarmDeliveryMode.INEXACT_ALLOW_WHILE_IDLE -> {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    plan.triggerAtMillis,
                    pendingIntent
                )
            }
        }
    }

    fun cancel(alarmManager: AlarmManager, pendingIntent: PendingIntent) {
        alarmManager.cancel(pendingIntent)
    }
}
