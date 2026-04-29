package com.novahorizon.wanderly.services

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `service uses eq filters for profile update subscriptions`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt")
            .readText()

        assertTrue(source.contains("filter(\"id\", FilterOperator.EQ"))
        assertFalse(source.contains("FilterOperator.IN"))
        assertFalse(source.contains("{${'$'}"))
        assertFalse(source.contains("filter = null"))
    }

    @Test
    fun `service does not subscribe to all profile updates`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt")
            .readText()
        val subscriptionBlock = source.substringAfter("postgresChangeFlow<PostgresAction.Update>")
            .substringBefore("}.onEach")

        assertTrue(subscriptionBlock.contains("table = Constants.TABLE_PROFILES"))
        assertTrue(subscriptionBlock.contains("filter(\"id\", FilterOperator.EQ"))
    }

    private fun projectFile(relativePath: String): File = File(projectRoot(), relativePath)

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Project root not found")
    }
}
