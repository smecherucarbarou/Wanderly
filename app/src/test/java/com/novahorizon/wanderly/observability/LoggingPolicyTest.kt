package com.novahorizon.wanderly.observability

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggingPolicyTest {

    @Test
    fun `production code logs through debug-gated AppLogger`() {
        val appLogger = projectFile("app/src/main/java/com/novahorizon/wanderly/observability/AppLogger.kt")
            .readText()

        assertTrue(appLogger.contains("import android.util.Log"))
        assertTrue(appLogger.contains("BuildConfig.DEBUG"))
        assertTrue(appLogger.contains("LogRedactor.redact"))

        val rawLogFiles = productionKotlinFiles()
            .filterNot { it.invariantSeparatorsPath.endsWith("observability/AppLogger.kt") }
            .filter { file ->
                val source = file.readText()
                source.contains("import android.util.Log") ||
                    source.contains("android.util.Log.") ||
                    RAW_LOG_CALL.containsMatchIn(source)
            }

        assertTrue(
            "Use AppLogger instead of direct android.util.Log in: " +
                rawLogFiles.joinToString { it.relativeTo(projectRoot()).invariantSeparatorsPath },
            rawLogFiles.isEmpty()
        )
    }

    private fun productionKotlinFiles(): List<File> {
        return projectFile("app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
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

    private companion object {
        private val RAW_LOG_CALL = Regex("""\bLog\.(d|i|w|e|v)\(""")
    }
}
