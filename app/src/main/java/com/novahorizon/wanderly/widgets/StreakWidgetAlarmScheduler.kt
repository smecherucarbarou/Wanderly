package com.novahorizon.wanderly.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build

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
        alarmManager: AlarmManager,
        pendingIntent: PendingIntent,
        nowMillis: Long = System.currentTimeMillis(),
        sdkInt: Int = Build.VERSION.SDK_INT
    ) {
        val canScheduleExactAlarms = if (sdkInt >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
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
