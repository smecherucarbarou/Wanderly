package com.novahorizon.wanderly.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class StreakWidgetAlarmSchedulerTest {

    @Test
    fun refreshIntervalUsesPolicySafeCadence() {
        assertEquals(60 * 60 * 1000L, StreakWidgetRefreshPolicy.REFRESH_INTERVAL_MILLIS)
    }

    @Test
    fun resolveSchedulePlanUsesInexactNonWakeupAlarm() {
        val plan = StreakWidgetAlarmScheduler.resolveSchedulePlan(
            nowElapsedMillis = 1_000L
        )

        assertEquals(3_601_000L, plan.triggerAtElapsedMillis)
        assertEquals(15 * 60 * 1000L, plan.windowLengthMillis)
        assertEquals(AlarmDeliveryMode.INEXACT_NON_WAKEUP, plan.deliveryMode)
    }

    @Test
    fun remoteRefreshPolicySkipsFreshCachedSnapshot() {
        val snapshot = com.novahorizon.wanderly.data.WidgetStreakSnapshot(
            streakCount = 5,
            lastMissionDate = "2026-04-26",
            savedAtMillis = 10_000L,
            lastSyncSucceeded = true
        )

        assertEquals(
            false,
            StreakWidgetRefreshPolicy.shouldFetchRemote(snapshot = snapshot, nowMillis = 11_000L)
        )
    }
}
