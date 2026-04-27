package com.novahorizon.wanderly.widgets

import android.app.AlarmManager
import android.app.PendingIntent

enum class AlarmDeliveryMode {
    INEXACT_NON_WAKEUP
}

data class AlarmSchedulePlan(
    val triggerAtElapsedMillis: Long,
    val windowLengthMillis: Long,
    val deliveryMode: AlarmDeliveryMode
)

object StreakWidgetAlarmScheduler {

    fun resolveSchedulePlan(nowElapsedMillis: Long): AlarmSchedulePlan {
        return AlarmSchedulePlan(
            triggerAtElapsedMillis = nowElapsedMillis + StreakWidgetRefreshPolicy.REFRESH_INTERVAL_MILLIS,
            windowLengthMillis = StreakWidgetRefreshPolicy.FLEX_WINDOW_MILLIS,
            deliveryMode = AlarmDeliveryMode.INEXACT_NON_WAKEUP
        )
    }

    fun scheduleNext(
        alarmManager: AlarmManager,
        pendingIntent: PendingIntent,
        nowElapsedMillis: Long = android.os.SystemClock.elapsedRealtime()
    ) {
        val plan = resolveSchedulePlan(nowElapsedMillis = nowElapsedMillis)

        when (plan.deliveryMode) {
            AlarmDeliveryMode.INEXACT_NON_WAKEUP -> {
                alarmManager.setWindow(
                    AlarmManager.ELAPSED_REALTIME,
                    plan.triggerAtElapsedMillis,
                    plan.windowLengthMillis,
                    pendingIntent
                )
            }
        }
    }

    fun cancel(alarmManager: AlarmManager, pendingIntent: PendingIntent) {
        alarmManager.cancel(pendingIntent)
    }
}
