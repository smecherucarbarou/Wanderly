package com.novahorizon.wanderly.util

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockUsageSourceTest {

    @Test
    fun `production code reads current time through Clock`() {
        val mainSource = File(projectRoot(), "app/src/main/java")
        val violations = mainSource.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("util/Clock.kt") }
            .filter { it.readText().contains("System.currentTimeMillis()") }
            .map { it.relativeTo(projectRoot()).invariantSeparatorsPath }
            .toList()

        assertTrue("Unexpected direct time reads: $violations", violations.isEmpty())
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
