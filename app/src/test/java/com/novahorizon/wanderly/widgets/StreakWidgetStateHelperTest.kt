package com.novahorizon.wanderly.widgets

import com.novahorizon.wanderly.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class StreakWidgetStateHelperTest {

    @Test
    fun resolveVisualStateReturnsNeutralMascotWhenStreakIsZero() {
        val now = LocalDateTime.of(2026, 4, 21, 11, 0)

        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 0,
            lastMissionDate = null,
            now = now
        )

        assertEquals(StreakTier.NONE, state.tier)
        assertEquals(WidgetMood.NEUTRAL, state.mood)
    }

    @Test
    fun resolveVisualStateReturnsProudWhenMissionWasCompletedToday() {
        val now = LocalDateTime.of(2026, 4, 21, 11, 0)

        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 8,
            lastMissionDate = now.toLocalDate().toString(),
            now = now
        )

        assertEquals(StreakTier.BLAZE, state.tier)
        assertEquals(WidgetMood.PROUD, state.mood)
        assertEquals(R.drawable.bg_widget_streak, state.backgroundRes)
        assertEquals(R.color.accent, state.countColorRes)
        assertEquals(R.color.pollen_white, state.subtitleColorRes)
        assertFalse(state.inDanger)
    }

    @Test
    fun resolveVisualStateReturnsWatchfulBeforeEveningWhenTodayMissionIsMissing() {
        val now = LocalDateTime.of(2026, 4, 21, 14, 0)

        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 12,
            lastMissionDate = now.toLocalDate().minusDays(1).toString(),
            now = now
        )

        assertEquals(StreakTier.BLAZE, state.tier)
        assertEquals(WidgetMood.WATCHFUL, state.mood)
        assertFalse(state.inDanger)
    }

    @Test
    fun resolveVisualStateReturnsStressedNearEndOfDayWhenStreakIsAtRisk() {
        val now = LocalDateTime.of(2026, 4, 21, 21, 30)

        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 58,
            lastMissionDate = now.toLocalDate().minusDays(1).toString(),
            now = now
        )

        assertEquals(StreakTier.LEGENDARY, state.tier)
        assertEquals(WidgetMood.STRESSED, state.mood)
        assertEquals(R.drawable.bg_widget_streak_warning, state.backgroundRes)
        assertEquals(R.color.primary, state.countColorRes)
        assertEquals(R.color.accent, state.subtitleColorRes)
        assertTrue(state.inDanger)
    }

    @Test
    fun resolveVisualStateReturnsNeutralWhenStoredDateShowsAlreadyLostStreak() {
        val now = LocalDateTime.of(2026, 4, 21, 21, 30)

        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 22,
            lastMissionDate = now.toLocalDate().minusDays(3).toString(),
            now = now
        )

        assertEquals(StreakTier.BLAZE, state.tier)
        assertEquals(WidgetMood.NEUTRAL, state.mood)
        assertFalse(state.inDanger)
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
}
