package com.novahorizon.wanderly.streak

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class DailyStreakStatus {
    INACTIVE,
    ACTIVE_TODAY,
    AT_RISK,
    FREEZE_ELIGIBLE,
    HARD_LOST
}

object DailyStreakStatusEvaluator {

    fun evaluate(
        streakCount: Int,
        lastMissionDate: String?,
        today: LocalDate = LocalDate.now()
    ): DailyStreakStatus {
        if (streakCount <= 0) return DailyStreakStatus.INACTIVE

        val lastMission = runCatching { lastMissionDate?.let(LocalDate::parse) }.getOrNull()
            ?: return DailyStreakStatus.HARD_LOST

        return when (ChronoUnit.DAYS.between(lastMission, today)) {
            0L -> DailyStreakStatus.ACTIVE_TODAY
            1L -> DailyStreakStatus.AT_RISK
            2L -> DailyStreakStatus.FREEZE_ELIGIBLE
            else -> DailyStreakStatus.HARD_LOST
        }
    }
}
