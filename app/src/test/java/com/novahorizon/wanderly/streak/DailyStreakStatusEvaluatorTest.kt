package com.novahorizon.wanderly.streak

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DailyStreakStatusEvaluatorTest {

    private val today = LocalDate.of(2026, 4, 23)

    @Test
    fun `returns inactive when streak count is zero`() {
        val status = DailyStreakStatusEvaluator.evaluate(
            streakCount = 0,
            lastMissionDate = null,
            today = today
        )

        assertEquals(DailyStreakStatus.INACTIVE, status)
    }

    @Test
    fun `returns active today when mission already happened`() {
        val status = DailyStreakStatusEvaluator.evaluate(
            streakCount = 9,
            lastMissionDate = today.toString(),
            today = today
        )

        assertEquals(DailyStreakStatus.ACTIVE_TODAY, status)
    }

    @Test
    fun `returns at risk when only today mission is missing`() {
        val status = DailyStreakStatusEvaluator.evaluate(
            streakCount = 9,
            lastMissionDate = today.minusDays(1).toString(),
            today = today
        )

        assertEquals(DailyStreakStatus.AT_RISK, status)
    }

    @Test
    fun `returns freeze eligible when exactly one day was missed`() {
        val status = DailyStreakStatusEvaluator.evaluate(
            streakCount = 9,
            lastMissionDate = today.minusDays(2).toString(),
            today = today
        )

        assertEquals(DailyStreakStatus.FREEZE_ELIGIBLE, status)
    }

    @Test
    fun `returns hard lost when two or more days were missed`() {
        val status = DailyStreakStatusEvaluator.evaluate(
            streakCount = 9,
            lastMissionDate = today.minusDays(3).toString(),
            today = today
        )

        assertEquals(DailyStreakStatus.HARD_LOST, status)
    }
}
