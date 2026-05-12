package com.novahorizon.wanderly.notifications

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationPermissionManagerTest {

    @Test
    @Config(sdk = [32], application = Application::class)
    fun `notification permission is not required before Android 13`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        assertEquals(
            NotificationPermissionManager.Status.NOT_REQUIRED,
            NotificationPermissionManager.status(context)
        )
    }

    @Test
    fun `notification permission reports denied when runtime permission is required and missing`() {
        assertEquals(
            NotificationPermissionManager.Status.DENIED,
            NotificationPermissionManager.resolveStatus(
                isRuntimePermissionRequired = true,
                isPermissionGranted = false
            )
        )
    }

    @Test
    fun `notification permission reports granted when runtime permission is required and granted`() {
        assertEquals(
            NotificationPermissionManager.Status.GRANTED,
            NotificationPermissionManager.resolveStatus(
                isRuntimePermissionRequired = true,
                isPermissionGranted = true
            )
        )
    }

    @Test
    @Config(sdk = [35], application = Application::class)
    fun `notification settings intent targets app notification settings`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        val intent = NotificationPermissionManager.notificationSettingsIntent(context)

        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `request action is none when permission is already available`() {
        assertEquals(
            NotificationPermissionManager.RequestAction.NONE,
            NotificationPermissionManager.resolveRequestAction(
                status = NotificationPermissionManager.Status.GRANTED,
                requestedBefore = true,
                shouldShowRationale = true
            )
        )
    }

    @Test
    fun `request action asks permission before first Android 13 prompt`() {
        assertEquals(
            NotificationPermissionManager.RequestAction.REQUEST_PERMISSION,
            NotificationPermissionManager.resolveRequestAction(
                status = NotificationPermissionManager.Status.DENIED,
                requestedBefore = false,
                shouldShowRationale = false
            )
        )
    }

    @Test
    fun `request action shows rationale when system recommends it`() {
        assertEquals(
            NotificationPermissionManager.RequestAction.SHOW_RATIONALE,
            NotificationPermissionManager.resolveRequestAction(
                status = NotificationPermissionManager.Status.DENIED,
                requestedBefore = false,
                shouldShowRationale = true
            )
        )
    }

    @Test
    fun `request action opens settings after prior denial without rationale`() {
        assertEquals(
            NotificationPermissionManager.RequestAction.OPEN_SETTINGS,
            NotificationPermissionManager.resolveRequestAction(
                status = NotificationPermissionManager.Status.DENIED,
                requestedBefore = true,
                shouldShowRationale = false
            )
        )
    }
}
