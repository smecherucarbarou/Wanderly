package com.novahorizon.wanderly.widgets

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ExactAlarmPermissionTest {

    private lateinit var context: Application
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setUp() {
        ShadowAlarmManager.reset()
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    @After
    fun tearDown() {
        ShadowAlarmManager.reset()
    }

    @Test
    fun `scheduleNext does not open exact alarm settings when permission is denied`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        StreakWidgetAlarmScheduler.scheduleNext(
            alarmManager = alarmManager,
            pendingIntent = pendingIntent(),
            nowElapsedMillis = 1_000L
        )

        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `scheduleNext schedules inexact non wakeup alarm`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        StreakWidgetAlarmScheduler.scheduleNext(
            alarmManager = alarmManager,
            pendingIntent = pendingIntent(),
            nowElapsedMillis = 1_000L
        )

        val alarms = shadowOf(alarmManager).scheduledAlarms
        val alarm = alarms.first()
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(1, alarms.size)
        assertEquals(AlarmManager.ELAPSED_REALTIME, alarm.getType())
        assertEquals(3_601_000L, alarm.getTriggerAtMs())
        assertEquals(false, alarm.isAllowWhileIdle)
    }

    private fun pendingIntent(): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            0,
            Intent("com.novahorizon.wanderly.TEST_STREAK_WIDGET"),
            PendingIntent.FLAG_IMMUTABLE
        )
    }
}
