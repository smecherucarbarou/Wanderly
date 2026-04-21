package com.novahorizon.wanderly.widgets

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

object StreakWidgetStateHelper {

    fun isInDanger(lastMissionDate: String?, today: LocalDate = LocalDate.now()): Boolean {
        if (lastMissionDate.isNullOrBlank()) return false

        val parsedDate = try {
            LocalDate.parse(lastMissionDate)
        } catch (_: DateTimeParseException) {
            return false
        }

        return ChronoUnit.DAYS.between(parsedDate, today) > 1
    }
}
