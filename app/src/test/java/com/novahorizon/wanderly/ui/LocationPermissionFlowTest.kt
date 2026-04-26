package com.novahorizon.wanderly.ui

import android.app.Application
import android.provider.Settings
import com.novahorizon.wanderly.ui.common.LocationPermissionController
import com.novahorizon.wanderly.ui.common.LocationPermissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocationPermissionFlowTest {

    @Test
    fun `permanently denied location opens settings instead of requesting permission again`() {
        val state = LocationPermissionGate.resolveState(
            hasPermission = false,
            hasRequestedBefore = true,
            shouldShowRationale = false
        )
        val settingsIntent = LocationPermissionController.appSettingsIntent(
            "com.novahorizon.wanderly"
        )

        assertEquals(LocationPermissionGate.State.SETTINGS, state)
        assertFalse(LocationPermissionGate.shouldLaunchSystemRequest(state))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settingsIntent.action)
        assertEquals("package:com.novahorizon.wanderly", settingsIntent.data.toString())
    }
}
