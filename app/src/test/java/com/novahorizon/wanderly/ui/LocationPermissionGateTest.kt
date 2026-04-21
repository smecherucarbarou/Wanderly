package com.novahorizon.wanderly.ui

import com.novahorizon.wanderly.ui.common.LocationPermissionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPermissionGateTest {

    @Test
    fun `returns granted when permission is already available`() {
        assertEquals(
            LocationPermissionGate.State.GRANTED,
            LocationPermissionGate.resolveState(
                hasPermission = true,
                hasRequestedBefore = false,
                shouldShowRationale = false
            )
        )
    }

    @Test
    fun `returns request when permission was never requested`() {
        assertEquals(
            LocationPermissionGate.State.REQUEST,
            LocationPermissionGate.resolveState(
                hasPermission = false,
                hasRequestedBefore = false,
                shouldShowRationale = false
            )
        )
    }

    @Test
    fun `returns rationale after a deny that can still be asked again`() {
        assertEquals(
            LocationPermissionGate.State.RATIONALE,
            LocationPermissionGate.resolveState(
                hasPermission = false,
                hasRequestedBefore = true,
                shouldShowRationale = true
            )
        )
    }

    @Test
    fun `returns settings when permission was denied permanently`() {
        assertEquals(
            LocationPermissionGate.State.SETTINGS,
            LocationPermissionGate.resolveState(
                hasPermission = false,
                hasRequestedBefore = true,
                shouldShowRationale = false
            )
        )
    }

    @Test
    fun `launches system request only for request and rationale states`() {
        assertTrue(LocationPermissionGate.shouldLaunchSystemRequest(LocationPermissionGate.State.REQUEST))
        assertTrue(LocationPermissionGate.shouldLaunchSystemRequest(LocationPermissionGate.State.RATIONALE))
        assertFalse(LocationPermissionGate.shouldLaunchSystemRequest(LocationPermissionGate.State.SETTINGS))
        assertFalse(LocationPermissionGate.shouldLaunchSystemRequest(LocationPermissionGate.State.GRANTED))
    }
}
