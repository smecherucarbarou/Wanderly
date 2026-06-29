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

    @Test
    fun `instant just before utc midnight stays on the same utc day`() {
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        assertEquals("2026-04-21", DateUtils.formatUtcDate(parser.parse("2026-04-21 23:59:59")!!))
    }

    @Test
    fun `instant just after utc midnight rolls to the next utc day`() {
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        assertEquals("2026-04-22", DateUtils.formatUtcDate(parser.parse("2026-04-22 00:00:01")!!))
    }

    @Test
    fun `negative-offset local evening maps to the next utc day`() {
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("America/New_York")
        }
        // 21:00 EDT (UTC-4) == 01:00 UTC the next day.
        assertEquals("2026-04-22", DateUtils.formatUtcDate(parser.parse("2026-04-21 21:00")!!))
    }
}
