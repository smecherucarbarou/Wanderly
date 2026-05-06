package com.novahorizon.wanderly.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiveRealtimeServiceFilterTest {

    @Test
    fun `profile subscriptions are planned as one bounded channel per friend id`() {
        val subscriptions = HiveRealtimeSubscriptionPlanner.subscriptionsFor(
            currentUserId = "self-id",
            friendIds = listOf("friend-a", "friend-b", "friend-a", "self-id", " ")
        )

        assertEquals(listOf("friend-a", "friend-b"), subscriptions.map { it.profileId })
        assertEquals(subscriptions.map { it.channelId }.distinct(), subscriptions.map { it.channelId })
        assertTrue(subscriptions.all { it.channelId.startsWith("hive_updates_") })
    }

    @Test
    fun `empty friend list produces no subscriptions`() {
        val subscriptions = HiveRealtimeSubscriptionPlanner.subscriptionsFor(
            currentUserId = "self-id",
            friendIds = emptyList()
        )

        assertTrue(subscriptions.isEmpty())
    }

    @Test
    fun `only self id produces no subscriptions`() {
        val subscriptions = HiveRealtimeSubscriptionPlanner.subscriptionsFor(
            currentUserId = "self-id",
            friendIds = listOf("self-id", "self-id")
        )

        assertTrue(subscriptions.isEmpty())
    }
}
