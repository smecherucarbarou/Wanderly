package com.novahorizon.wanderly.widgets

import com.novahorizon.wanderly.data.WidgetStreakSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StreakWidgetRenderTest {

    @Test
    fun `resolveVisualState produces valid state for active streak`() {
        val state = StreakWidgetStateHelper.resolveVisualState(
            streakCount = 7,
            lastMissionDate = LocalDate.now().toString()
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
        val twoDaysAgo = LocalDate.now().minusDays(2).toString()
        assertTrue(StreakWidgetStateHelper.isInDanger(twoDaysAgo))
    }

    @Test
    fun `isInDanger returns false for null mission date`() {
        assertFalse(StreakWidgetStateHelper.isInDanger(null))
    }
}
