package com.novahorizon.wanderly.api

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiClientSourceTest {

    @Test
    fun `android gemini client logs proxy ownership instead of model ownership`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/api/GeminiClient.kt").readText()

        assertTrue(source.contains("Starting Gemini \$logLabel proxy request bodyLength="))
        assertFalse(source.contains("PRIMARY_MODEL"))
        assertFalse(source.contains("FALLBACK_MODEL"))
        assertFalse(source.contains("model=\$PRIMARY_MODEL"))
        assertFalse(source.contains("fallback=\$FALLBACK_MODEL"))
    }

    private fun projectFile(relativePath: String): File {
        return File(projectRoot(), relativePath)
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }
}
