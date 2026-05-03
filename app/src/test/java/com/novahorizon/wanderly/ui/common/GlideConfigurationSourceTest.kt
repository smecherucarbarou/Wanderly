package com.novahorizon.wanderly.ui.common

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideConfigurationSourceTest {

    @Test
    fun `glide compiler is wired through ksp`() {
        val buildGradle = projectFile("app/build.gradle.kts").readText()
        val versions = projectFile("gradle/libs.versions.toml").readText()

        assertTrue(buildGradle.contains("implementation(libs.glide)"))
        assertTrue(buildGradle.contains("ksp(libs.glide.ksp)"))
        assertTrue(versions.contains("glide-ksp"))
    }

    @Test
    fun `single glide module lives in root package`() {
        val rootModule = projectFile("app/src/main/java/com/novahorizon/wanderly/WanderlyGlideModule.kt")
        val uiModule = projectFile("app/src/main/java/com/novahorizon/wanderly/ui/WanderlyGlideModule.kt")
        val glideModules = projectFile("app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("@GlideModule") }
            .toList()

        assertTrue(rootModule.isFile)
        assertFalse(uiModule.exists())
        assertEquals(1, glideModules.size)
        assertTrue(rootModule.readText().contains("package com.novahorizon.wanderly"))
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
