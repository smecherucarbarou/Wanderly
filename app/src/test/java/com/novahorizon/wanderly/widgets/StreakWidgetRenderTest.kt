package com.novahorizon.wanderly.widgets

import com.novahorizon.wanderly.data.WidgetStreakSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreakWidgetRenderTest {

    @Test
    fun `resolveVisualState produces valid state for active streak`() {
        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 7,
            lastMissionDate = "2026-05-05"
        )

        assertNotNull(state)
        assertNotNull(state.mood)
    }

    @Test
    fun `resolveVisualState produces valid state for zero streak`() {
        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 0,
            lastMissionDate = null
        )

        assertNotNull(state)
        assertNotNull(state.mood)
    }

    @Test
    fun `resolveVisualState with snapshot handles null snapshot`() {
        val state = StreakWidgetStateHelper.resolveVisualState(
            snapshot = null,
            currentFetchSucceeded = false
        )

        assertNotNull(state)
    }

    @Test
    fun `isInDanger returns true when streak not maintained today`() {
        assertTrue(StreakWidgetStateHelper.isInDanger("2026-05-04"))
    }

    @Test
    fun `isInDanger returns false for null mission date`() {
        assertFalse(StreakWidgetStateHelper.isInDanger(null))
    }
}
