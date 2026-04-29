package com.novahorizon.wanderly.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiveRealtimeServiceReconnectPolicyTest {

    @Test
    fun `invalid subscription errors are bounded before disabling realtime`() {
        val policy = HiveRealtimeReconnectPolicy(maxInvalidSubscriptionRetries = 3)
        val error = IllegalStateException("Unable to subscribe to changes with given parameters")

        assertEquals(RealtimeRecoveryAction.RetryInvalidSubscription, policy.recordSubscriptionFailure(error))
        assertEquals(RealtimeRecoveryAction.RetryInvalidSubscription, policy.recordSubscriptionFailure(error))
        assertEquals(RealtimeRecoveryAction.DisableRealtimeForSession, policy.recordSubscriptionFailure(error))
        assertTrue(policy.realtimeDisabledForSession)
    }

    @Test
    fun `network retries use capped exponential backoff without disabling realtime`() {
        val policy = HiveRealtimeReconnectPolicy()

        assertEquals(1_000L, policy.networkRetryDelayMs(0))
        assertEquals(2_000L, policy.networkRetryDelayMs(1))
        assertEquals(4_000L, policy.networkRetryDelayMs(2))
        assertEquals(30_000L, policy.networkRetryDelayMs(10))
        assertFalse(policy.realtimeDisabledForSession)
    }

    @Test
    fun `auth token errors retry only once before session fallback`() {
        val policy = HiveRealtimeReconnectPolicy()

        assertEquals(RealtimeRecoveryAction.RefreshTokenAndRetry, policy.recordSubscriptionFailure(IllegalStateException("JWT expired")))
        assertEquals(RealtimeRecoveryAction.DisableRealtimeForSession, policy.recordSubscriptionFailure(IllegalStateException("access token invalid")))
        assertTrue(policy.realtimeDisabledForSession)
    }
}
