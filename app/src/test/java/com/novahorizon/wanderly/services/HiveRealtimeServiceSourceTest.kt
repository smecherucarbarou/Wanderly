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
