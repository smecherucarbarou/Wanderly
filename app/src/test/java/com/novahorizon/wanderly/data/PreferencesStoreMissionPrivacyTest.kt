package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesStoreMissionPrivacyTest {

    @Test
    fun `mission snapshots are not persisted to plaintext DataStore`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt")
            .readText()
        val saveMissionData = source.substringAfter("suspend fun saveMissionData(")
            .substringBefore("suspend fun clearMissionData()")

        assertTrue(source.contains("data class MissionSnapshot"))
        assertTrue(source.contains("private var missionSnapshot"))
        assertFalse(saveMissionData.contains("putMainString(Constants.KEY_MISSION_TEXT"))
        assertFalse(saveMissionData.contains("putMainString(Constants.KEY_MISSION_TARGET"))
        assertFalse(saveMissionData.contains("putMainString(Constants.KEY_MISSION_CITY"))
        assertFalse(saveMissionData.contains("putMainString(Constants.KEY_MISSION_HISTORY"))
        assertFalse(saveMissionData.contains("putMainFloat(Constants.KEY_MISSION_TARGET_LAT_TYPED"))
        assertFalse(saveMissionData.contains("putMainFloat(Constants.KEY_MISSION_TARGET_LNG_TYPED"))
    }

    @Test
    fun `clearing mission data also removes legacy mission history`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/data/PreferencesStore.kt")
            .readText()
        val clearMissionData = source.substringAfter("suspend fun clearMissionData()")
            .substringBefore("suspend fun getNotificationCooldown")

        assertTrue(clearMissionData.contains("missionSnapshot = null"))
        assertTrue(clearMissionData.contains("Constants.KEY_MISSION_HISTORY"))
        assertTrue(clearMissionData.contains("Constants.KEY_MISSION_TARGET_LAT_TYPED"))
        assertTrue(clearMissionData.contains("Constants.KEY_MISSION_TARGET_LNG_TYPED"))
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
