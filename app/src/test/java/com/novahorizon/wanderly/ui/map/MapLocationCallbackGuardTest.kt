package com.novahorizon.wanderly.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLocationCallbackGuardTest {

    @Test
    fun `ignores location callback when fragment view is already destroyed`() {
        assertFalse(
            MapLocationCallbackGuard.shouldHandleLocationUpdate(
                hasLocation = true,
                isFragmentAdded = true,
                hasBinding = false
            )
        )
    }

    @Test
    fun `ignores location callback when fragment is detached`() {
        assertFalse(
            MapLocationCallbackGuard.shouldHandleLocationUpdate(
                hasLocation = true,
                isFragmentAdded = false,
                hasBinding = true
            )
        )
    }

    @Test
    fun `handles location callback only when fragment and view are still available`() {
        assertTrue(
            MapLocationCallbackGuard.shouldHandleLocationUpdate(
                hasLocation = true,
                isFragmentAdded = true,
                hasBinding = true
            )
        )
    }
}
