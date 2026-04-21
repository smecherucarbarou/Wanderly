package com.novahorizon.wanderly.widgets

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StreakWidgetStateHelperTest {

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
