package com.novahorizon.wanderly.di

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionHiltGraphSourceTest {

    @Test
    fun missionSelectorIsProvidedFromViewModelHiltGraph() {
        val missionModule = projectFile("app/src/main/java/com/novahorizon/wanderly/di/MissionModule.kt").readText()
        val missionsViewModel = projectFile("app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsViewModel.kt").readText()

        assertTrue(missionModule.contains("@InstallIn(ViewModelComponent::class)"))
        assertTrue(missionModule.contains("fun provideMissionPlaceSelector("))
        assertTrue(missionModule.contains("): MissionPlaceSelecting = MissionPlaceSelector("))
        assertTrue(missionsViewModel.contains("@HiltViewModel"))
        assertTrue(missionsViewModel.contains("private val missionPlaceSelector: MissionPlaceSelecting"))
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
