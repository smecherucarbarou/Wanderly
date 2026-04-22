package com.novahorizon.wanderly.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakWidgetAlarmSchedulerTest {

    @Test
    fun refreshIntervalIsFifteenSeconds() {
        assertEquals(15_000L, StreakWidgetAlarmScheduler.REFRESH_INTERVAL_MILLIS)
    }

    @Test
    fun resolveSchedulePlanUsesExactAlarmsOnAndroid12WhenAvailable() {
        val plan = StreakWidgetAlarmScheduler.resolveSchedulePlan(
            nowMillis = 1_000L,
            sdkInt = 31,
            canScheduleExactAlarms = true
        )

        assertEquals(16_000L, plan.triggerAtMillis)
        assertTrue(plan.useExactAlarm)
        assertEquals(AlarmDeliveryMode.EXACT_ALLOW_WHILE_IDLE, plan.deliveryMode)
    }

    @Test
    fun resolveSchedulePlanFallsBackToInexactAlarmsOnAndroid12WhenUnavailable() {
        val plan = StreakWidgetAlarmScheduler.resolveSchedulePlan(
            nowMillis = 1_000L,
            sdkInt = 31,
            canScheduleExactAlarms = false
        )

        assertEquals(16_000L, plan.triggerAtMillis)
        assertFalse(plan.useExactAlarm)
        assertEquals(AlarmDeliveryMode.INEXACT_ALLOW_WHILE_IDLE, plan.deliveryMode)
    }

    @Test
    fun resolveSchedulePlanUsesExactAlarmsBeforeAndroid12WithoutPermissionGate() {
        val plan = StreakWidgetAlarmScheduler.resolveSchedulePlan(
            nowMillis = 1_000L,
            sdkInt = 30,
            canScheduleExactAlarms = false
        )

        assertTrue(plan.useExactAlarm)
        assertEquals(AlarmDeliveryMode.EXACT_ALLOW_WHILE_IDLE, plan.deliveryMode)
    }
}
