package com.novahorizon.wanderly.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class DateUtilsTest {

    @Test
    fun `formats dates in utc calendar day`() {
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Europe/Bucharest")
        }

        val localLateNight = parser.parse("2026-04-22 01:30")!!

        assertEquals("2026-04-21", DateUtils.formatUtcDate(localLateNight))
    }
}
