package com.novahorizon.wanderly.data.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PlusVisibilityTest {

    private val now = Instant.parse("2026-05-13T12:00:00Z")

    @Test
    fun `new free user has no plus badge`() {
        assertFalse(PlusEntitlement.free().shouldShowActiveBadge(now))
    }

    @Test
    fun `active plus user has plus badge`() {
        val entitlement = PlusEntitlement(
            isPlus = true,
            status = "active",
            provider = "manual",
            productId = "wanderly_plus_monthly",
            entitlement = "wanderly_plus",
            currentPeriodEnd = Instant.parse("2026-06-13T12:00:00Z")
        )

        assertTrue(entitlement.shouldShowActiveBadge(now))
    }

    @Test
    fun `expired user has no plus badge`() {
        val entitlement = PlusEntitlement(
            isPlus = true,
            status = "active",
            provider = "manual",
            productId = "wanderly_plus_monthly",
            entitlement = "wanderly_plus",
            currentPeriodEnd = Instant.parse("2026-05-12T12:00:00Z")
        )

        assertFalse(entitlement.shouldShowActiveBadge(now))
    }

    @Test
    fun `revoked user has no plus badge even with future period`() {
        val entitlement = PlusEntitlement(
            isPlus = true,
            status = "revoked",
            provider = "manual",
            productId = "wanderly_plus_monthly",
            entitlement = "wanderly_plus",
            currentPeriodEnd = Instant.parse("2026-06-13T12:00:00Z")
        )

        assertFalse(entitlement.shouldShowActiveBadge(now))
    }
}
