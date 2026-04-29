package com.novahorizon.wanderly.ui.missions

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionCameraPermissionUxTest {

    @Test
    fun `camera permission flow includes rationale and settings fallback`() {
        val fragment = projectFile(
            "app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt"
        ).readText()
        val strings = projectFile("app/src/main/res/values/strings.xml").readText()

        assertTrue(fragment.contains("shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)"))
        assertTrue(fragment.contains("markCameraPermissionRequested()"))
        assertTrue(fragment.contains("openCameraPermissionSettings()"))
        assertTrue(fragment.contains("mission_camera_permission_rationale"))
        assertTrue(fragment.contains("mission_camera_permission_settings"))
        assertTrue(strings.contains("mission_camera_permission_rationale"))
        assertTrue(strings.contains("mission_camera_permission_settings"))
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
