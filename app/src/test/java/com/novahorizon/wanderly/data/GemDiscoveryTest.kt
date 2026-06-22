package com.novahorizon.wanderly.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemDiscoveryTest {

    @Test
    fun `within range at 100 meters`() {
        assertTrue(GemProximity.isWithinRange(0.1))
    }

    @Test
    fun `within range exactly at threshold`() {
        assertTrue(GemProximity.isWithinRange(GemProximity.DISCOVER_RADIUS_M / 1000.0))
    }

    @Test
    fun `out of range beyond threshold`() {
        assertFalse(GemProximity.isWithinRange(0.121))
    }

    @Test
    fun `success response maps to Success with reward`() {
        val result = mapDiscoverGemResponse(
            DiscoverGemRpcResponse(success = true, reward_honey = 10, gem_id = "abc")
        )
        assertEquals(GemDiscoveryResult.Success(10), result)
    }

    @Test
    fun `success without reward defaults to zero`() {
        val result = mapDiscoverGemResponse(DiscoverGemRpcResponse(success = true))
        assertEquals(GemDiscoveryResult.Success(0), result)
    }

    @Test
    fun `already_discovered error maps to AlreadyDiscovered`() {
        val result = mapDiscoverGemResponse(
            DiscoverGemRpcResponse(success = false, error = "already_discovered")
        )
        assertEquals(GemDiscoveryResult.AlreadyDiscovered, result)
    }

    @Test
    fun `not_authenticated error maps to Unauthenticated`() {
        val result = mapDiscoverGemResponse(
            DiscoverGemRpcResponse(success = false, error = "not_authenticated")
        )
        assertEquals(GemDiscoveryResult.Unauthenticated, result)
    }

    @Test
    fun `unknown error maps to Error`() {
        val result = mapDiscoverGemResponse(
            DiscoverGemRpcResponse(success = false, error = "invalid_place")
        )
        assertEquals(GemDiscoveryResult.Error, result)
    }
}
