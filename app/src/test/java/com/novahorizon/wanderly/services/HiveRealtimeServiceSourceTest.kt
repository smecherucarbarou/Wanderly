package com.novahorizon.wanderly.services

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiveRealtimeServiceSourceTest {

    @Test
    fun `onDestroy does not block the main thread while unsubscribing`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt")
            .readText()

        assertFalse(source.contains("import kotlinx.coroutines.runBlocking"))
        assertFalse(source.contains("runBlocking"))
        assertTrue(source.contains("serviceScope.launch"))
        assertTrue(source.contains("withTimeoutOrNull(2_000L)"))
    }

    @Test
    fun `profile realtime is disabled for demo before connecting to Supabase`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt")
            .readText()
        val observeHiveChanges = source.substringAfter("private fun observeHiveChanges()")
            .substringBefore("private suspend fun subscribeWithFallback")

        assertTrue(source.contains("private const val ENABLE_PROFILE_REALTIME = false"))
        assertTrue(observeHiveChanges.contains("if (!ENABLE_PROFILE_REALTIME)"))
        assertTrue(
            observeHiveChanges.indexOf("if (!ENABLE_PROFILE_REALTIME)") <
                observeHiveChanges.indexOf("SupabaseClient.client.realtime.connect()")
        )
    }

    @Test
    fun `setupSubscription catches sdk state errors and disables realtime`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt")
            .readText()
        val setupSubscription = source.substringAfter("private suspend fun setupSubscription")
            .substringBefore("private suspend fun setupSubscriptionInternal")

        assertTrue(setupSubscription.contains("catch (e: IllegalStateException)"))
        assertTrue(setupSubscription.contains("disableRealtimeForSession()"))
        assertTrue(source.contains("private var realtimeSetupFailures = 0"))
        assertTrue(source.contains("private val maxRealtimeSetupFailures = 2"))
    }

    @Test
    fun `postgres flow is configured before channel subscribe`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/services/HiveRealtimeService.kt")
            .readText()
        val setupInternal = source.substringAfter("private suspend fun setupSubscriptionInternal")
            .substringBefore("private suspend fun handleSubscriptionFailure")

        assertTrue(setupInternal.indexOf("postgresChangeFlow<PostgresAction.Update>") >= 0)
        assertTrue(setupInternal.indexOf("postgresChangeFlow<PostgresAction.Update>") < setupInternal.indexOf("channel.subscribe"))
        assertTrue(setupInternal.indexOf("channel.subscribe") < setupInternal.indexOf("profileChanges.onEach"))
    }

    private fun projectFile(relativePath: String): File {
        return File(projectRoot(), relativePath)
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        var current: File? = File(userDir).absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("Project root not found")
    }
}
