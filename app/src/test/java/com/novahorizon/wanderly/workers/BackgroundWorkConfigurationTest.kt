package com.novahorizon.wanderly.workers

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundWorkConfigurationTest {

    @Test
    fun `periodic background workers require network and healthy battery`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/WanderlyApplication.kt").readText()

        assertTrue(source.contains("Constraints.Builder()"))
        assertTrue(source.contains("setRequiredNetworkType(NetworkType.CONNECTED)"))
        assertTrue(source.contains("setRequiresBatteryNotLow(true)"))
        assertTrue(source.contains(".setConstraints(backgroundWorkConstraints)"))
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
