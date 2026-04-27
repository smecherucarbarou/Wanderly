package com.novahorizon.wanderly.widgets

import com.novahorizon.wanderly.data.WidgetStreakSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakWidgetRefreshPolicyTest {

    @Test
    fun shouldFetchRemoteWhenNoSnapshotExists() {
        assertTrue(StreakWidgetRefreshPolicy.shouldFetchRemote(snapshot = null, nowMillis = 1_000L))
    }

    @Test
    fun shouldNotFetchRemoteWhenSnapshotIsFresh() {
        val snapshot = WidgetStreakSnapshot(
            streakCount = 12,
            lastMissionDate = "2026-04-26",
            savedAtMillis = 10_000L,
            lastSyncSucceeded = true
        )

        assertFalse(
            StreakWidgetRefreshPolicy.shouldFetchRemote(
                snapshot = snapshot,
                nowMillis = 10_000L + StreakWidgetRefreshPolicy.MIN_REMOTE_REFRESH_INTERVAL_MILLIS - 1
            )
        )
    }

    @Test
    fun shouldFetchRemoteWhenSnapshotIsStale() {
        val snapshot = WidgetStreakSnapshot(
            streakCount = 12,
            lastMissionDate = "2026-04-26",
            savedAtMillis = 10_000L,
            lastSyncSucceeded = true
        )

        assertTrue(
            StreakWidgetRefreshPolicy.shouldFetchRemote(
                snapshot = snapshot,
                nowMillis = 10_000L + StreakWidgetRefreshPolicy.MIN_REMOTE_REFRESH_INTERVAL_MILLIS
            )
        )
    }
}
