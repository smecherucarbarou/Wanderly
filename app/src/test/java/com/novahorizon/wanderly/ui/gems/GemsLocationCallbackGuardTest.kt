package com.novahorizon.wanderly.ui.gems

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemsLocationCallbackGuardTest {

    @Test
    fun `ignores location success when fragment view is already destroyed`() {
        assertFalse(
            GemsLocationCallbackGuard.shouldHandleLocationSuccess(
                hasLocation = true,
                isFragmentAdded = true,
                hasBinding = false,
                hasLifecycleOwner = false
            )
        )
    }

    @Test
    fun `ignores location success when lifecycle owner is unavailable`() {
        assertFalse(
            GemsLocationCallbackGuard.shouldHandleLocationSuccess(
                hasLocation = true,
                isFragmentAdded = true,
                hasBinding = true,
                hasLifecycleOwner = false
            )
        )
    }

    @Test
    fun `handles location success only while fragment view is active`() {
        assertTrue(
            GemsLocationCallbackGuard.shouldHandleLocationSuccess(
                hasLocation = true,
                isFragmentAdded = true,
                hasBinding = true,
                hasLifecycleOwner = true
            )
        )
    }

    @Test
    fun `handles location failure only while fragment view is active`() {
        assertFalse(
            GemsLocationCallbackGuard.shouldHandleLocationFailure(
                isFragmentAdded = true,
                hasBinding = false
            )
        )

        assertTrue(
            GemsLocationCallbackGuard.shouldHandleLocationFailure(
                isFragmentAdded = true,
                hasBinding = true
            )
        )
    }
}
