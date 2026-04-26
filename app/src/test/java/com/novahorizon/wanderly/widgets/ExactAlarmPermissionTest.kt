package com.novahorizon.wanderly.widgets

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
    fun `scheduleNext opens exact alarm settings when permission is denied`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        StreakWidgetAlarmScheduler.scheduleNext(
            context = context,
            alarmManager = alarmManager,
            pendingIntent = pendingIntent(),
            nowMillis = 1_000L,
            sdkInt = 31
        )

        val startedIntent = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, startedIntent.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(0, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `scheduleNext schedules exact alarm when permission is granted`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        StreakWidgetAlarmScheduler.scheduleNext(
            context = context,
            alarmManager = alarmManager,
            pendingIntent = pendingIntent(),
            nowMillis = 1_000L,
            sdkInt = 31
        )

        val alarms = shadowOf(alarmManager).scheduledAlarms
        val alarm = alarms.first()
        assertNull(shadowOf(context).nextStartedActivity)
        assertEquals(1, alarms.size)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())
        assertEquals(16_000L, alarm.getTriggerAtMs())
        assertEquals(true, alarm.isAllowWhileIdle)
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
