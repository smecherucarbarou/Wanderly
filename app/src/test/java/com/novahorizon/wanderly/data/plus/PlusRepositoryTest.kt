package com.novahorizon.wanderly.data.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlusRepositoryTest {

    @Test
    fun `active rpc response maps snake case fields to plus entitlement`() {
        val entitlement = PlusRepository.mapEntitlementResponse(
            response = PlusRepository.PlusEntitlementResponse(
                is_plus = true,
                status = "active",
                provider = "supabase_dev",
                product_id = "wanderly_plus_monthly",
                entitlement = "wanderly_plus",
                current_period_end = "2026-12-31T23:59:59Z"
            ),
            now = Instant.parse("2026-05-13T12:00:00Z")
        )

        assertTrue(entitlement.isPlus)
        assertEquals("active", entitlement.status)
        assertEquals("supabase_dev", entitlement.provider)
        assertEquals("wanderly_plus_monthly", entitlement.productId)
        assertEquals("wanderly_plus", entitlement.entitlement)
        assertEquals(Instant.parse("2026-12-31T23:59:59Z"), entitlement.currentPeriodEnd)
    }

    @Test
    fun `free response maps to inactive plus entitlement`() {
        val entitlement = PlusRepository.mapEntitlementResponse(
            response = PlusRepository.PlusEntitlementResponse(
                is_plus = false,
                status = null,
                provider = null,
                product_id = null,
                entitlement = null,
                current_period_end = null
            ),
            now = Instant.parse("2026-05-13T12:00:00Z")
        )

        assertFalse(entitlement.isPlus)
        assertNull(entitlement.status)
        assertNull(entitlement.provider)
        assertNull(entitlement.productId)
        assertNull(entitlement.entitlement)
        assertNull(entitlement.currentPeriodEnd)
    }

    @Test
    fun `null current period end is handled safely`() {
        val entitlement = PlusRepository.mapEntitlementResponse(
            response = PlusRepository.PlusEntitlementResponse(
                is_plus = true,
                status = "active",
                provider = "manual",
                product_id = "wanderly_plus_manual",
                entitlement = "wanderly_plus",
                current_period_end = null
            ),
            now = Instant.parse("2026-05-13T12:00:00Z")
        )

        assertTrue(entitlement.isPlus)
        assertNull(entitlement.currentPeriodEnd)
    }
}
