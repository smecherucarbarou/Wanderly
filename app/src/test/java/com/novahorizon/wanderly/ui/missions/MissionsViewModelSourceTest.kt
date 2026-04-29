package com.novahorizon.wanderly.ui.missions

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionsViewModelSourceTest {

    @Test
    fun `destination verification failure does not use old hard throw`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsViewModel.kt")
            .readText()
        val generateMission = source.substringAfter("fun generateMission(")
            .substringBefore("fun verifyPhoto(")

        assertFalse(generateMission.contains("Could not verify the mission destination. Please try again."))
        assertFalse(generateMission.contains("throw Exception(\"Could not verify"))
        assertTrue(generateMission.contains("buildFallbackMission("))
        assertTrue(generateMission.contains("Could not verify a specific destination"))
    }

    @Test
    fun `mission generation no longer asks ai for one trusted target`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsViewModel.kt")
            .readText()
        val generateMission = source.substringAfter("fun generateMission(")
            .substringBefore("fun verifyPhoto(")

        assertTrue(generateMission.contains("missionCandidateProvider.generateCandidates"))
        assertTrue(generateMission.contains("missionPlaceSelector.selectBestMissionPlace"))
        assertFalse(generateMission.contains("Find ONE real public place"))
        assertFalse(generateMission.contains("Choose ONE exact place"))
        assertFalse(generateMission.contains("targetName"))
        assertFalse(generateMission.contains("resolveCoordinates("))
    }

    private fun projectFile(relativePath: String): File = File(projectRoot(), relativePath)

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Project root not found")
    }
}
