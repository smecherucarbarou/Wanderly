package com.novahorizon.wanderly.widgets

import com.novahorizon.wanderly.data.WidgetStreakSnapshot
import com.novahorizon.wanderly.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StreakWidgetStateHelperTest {

    @Test
    fun resolveVisualStateReturnsNeutralMascotWhenStreakIsZero() {
        val now = LocalDateTime.of(2026, 4, 21, 11, 0)

        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 0,
            lastMissionDate = null,
            now = now
        )

        assertEquals("Broken", state.tier.label)
        assertEquals(WidgetMood.NEUTRAL, state.mood)
        assertEquals("Start again today.", state.message)
        assertFalse(state.showStaleIndicator)
    }

    @Test
    fun resolveVisualStateReturnsProudWhenMissionWasCompletedToday() {
        val now = LocalDateTime.of(2026, 4, 21, 11, 0)

        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 8,
            lastMissionDate = now.toLocalDate().toString(),
            now = now
        )

        assertEquals("Rising", state.tier.label)
        assertEquals(WidgetMood.PROUD, state.mood)
        assertEquals(R.drawable.bg_widget_streak, state.backgroundRes)
        assertEquals(R.color.accent, state.countColorRes)
        assertEquals(R.color.pollen_white, state.subtitleColorRes)
        assertFalse(state.inDanger)
        assertFalse(state.showStaleIndicator)
        assertFalse(state.message?.isNotEmpty() == true)
    }

    @Test
    fun resolveVisualStateReturnsWatchfulBeforeEveningWhenTodayMissionIsMissing() {
        val now = LocalDateTime.of(2026, 4, 21, 14, 0)

        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 12,
            lastMissionDate = now.toLocalDate().minusDays(1).toString(),
            now = now
        )

        assertEquals("Rising", state.tier.label)
        assertEquals(WidgetMood.WATCHFUL, state.mood)
        assertFalse(state.inDanger)
        assertFalse(state.showStaleIndicator)
    }

    @Test
    fun resolveVisualStateReturnsStressedNearEndOfDayWhenStreakIsAtRisk() {
        val now = LocalDateTime.of(2026, 4, 21, 21, 30)

        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 58,
            lastMissionDate = now.toLocalDate().minusDays(1).toString(),
            now = now
        )

        assertEquals("Blazing", state.tier.label)
        assertEquals(WidgetMood.STRESSED, state.mood)
        assertEquals(R.drawable.bg_widget_streak_warning, state.backgroundRes)
        assertEquals(R.color.primary, state.countColorRes)
        assertEquals(R.color.accent, state.subtitleColorRes)
        assertTrue(state.inDanger)
        assertFalse(state.showStaleIndicator)
    }

    @Test
    fun resolveVisualStateReturnsNeutralWhenStoredDateShowsAlreadyLostStreak() {
        val now = LocalDateTime.of(2026, 4, 21, 21, 30)

        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 22,
            lastMissionDate = now.toLocalDate().minusDays(3).toString(),
            now = now
        )

        assertEquals("Broken", state.tier.label)
        assertEquals(WidgetMood.NEUTRAL, state.mood)
        assertFalse(state.inDanger)
        assertFalse(state.showStaleIndicator)
    }

    @Test
    fun resolveVisualStateShowsStaleIndicatorWhenSnapshotIsOlderThanFiveMinutesAndFetchFails() {
        val now = LocalDateTime.of(2026, 4, 21, 21, 30)
        val snapshot = WidgetStreakSnapshot(
            streakCount = 12,
            lastMissionDate = now.toLocalDate().minusDays(1).toString(),
            savedAtMillis = now.minusMinutes(6).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            lastSyncSucceeded = true
        )

        val state = StreakWidgetStateHelper.resolveVisualState(
            snapshot = snapshot,
            currentFetchSucceeded = false,
            now = now
        )

        assertTrue(state.showStaleIndicator)
    }

    @Test
    fun resolveVisualStateKeepsStaleIndicatorOffWhenSnapshotIsFreshAndFetchFails() {
        val now = LocalDateTime.of(2026, 4, 21, 21, 30)
        val snapshot = WidgetStreakSnapshot(
            streakCount = 12,
            lastMissionDate = now.toLocalDate().minusDays(1).toString(),
            savedAtMillis = now.minusMinutes(4).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            lastSyncSucceeded = true
        )

        val state = StreakWidgetStateHelper.resolveVisualState(
            snapshot = snapshot,
            currentFetchSucceeded = false,
            now = now
        )

        assertFalse(state.showStaleIndicator)
    }

    @Test
    fun isInDangerReturnsTrueWhenLastMissionWasTwoDaysAgo() {
        val today = LocalDate.of(2026, 4, 21)

        val result = StreakWidgetStateHelper.isInDanger(
            lastMissionDate = today.minusDays(2).toString(),
            today = today
        )

        assertTrue(result)
    }

    @Test
    fun isInDangerReturnsFalseWhenLastMissionWasToday() {
        val today = LocalDate.of(2026, 4, 21)

        val result = StreakWidgetStateHelper.isInDanger(
            lastMissionDate = today.toString(),
            today = today
        )

        assertFalse(result)
    }

    @Test
    fun resolveFrameIndexRotatesPredictablyAcrossThreeFrames() {
        assertEquals(0, StreakWidgetStateHelper.resolveFrameIndex(0L))
        assertEquals(1, StreakWidgetStateHelper.resolveFrameIndex(StreakWidgetRefreshPolicy.REFRESH_INTERVAL_MILLIS))
        assertEquals(2, StreakWidgetStateHelper.resolveFrameIndex(StreakWidgetRefreshPolicy.REFRESH_INTERVAL_MILLIS * 2))
        assertEquals(0, StreakWidgetStateHelper.resolveFrameIndex(StreakWidgetRefreshPolicy.REFRESH_INTERVAL_MILLIS * 3))
    }
}
