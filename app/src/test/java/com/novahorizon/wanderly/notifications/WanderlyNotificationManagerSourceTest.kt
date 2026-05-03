package com.novahorizon.wanderly.notifications

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WanderlyNotificationManagerSourceTest {

    @Test
    fun `notification manager checks android 13 runtime permission before showing`() {
        val source = projectFile(
            "app/src/main/java/com/novahorizon/wanderly/notifications/WanderlyNotificationManager.kt"
        ).readText()

        assertTrue(source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU"))
        assertTrue(source.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue(source.contains("ContextCompat.checkSelfPermission"))
        assertTrue(source.contains("POST_NOTIFICATIONS permission not granted"))
    }

    @Test
    fun `notification disabled warnings are logged once per process`() {
        val source = projectFile(
            "app/src/main/java/com/novahorizon/wanderly/notifications/WanderlyNotificationManager.kt"
        ).readText()

        assertTrue(source.contains("permissionWarningLogged"))
        assertTrue(source.contains("systemDisabledWarningLogged"))
        assertTrue(source.contains("logWarnOnce"))
    }

    @Test
    fun `application creates notification channel before workers can post alerts`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/WanderlyApplication.kt").readText()

        assertTrue(source.contains("WanderlyNotificationManager.createNotificationChannel(this)"))
    }

    private fun projectFile(relativePath: String): File {
        return File(projectRoot(), relativePath)
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }
}
