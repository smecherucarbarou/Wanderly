package com.novahorizon.wanderly.ui.missions

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionCompletionArchitectureTest {

    @Test
    fun `missions view model delegates completion to repository instead of computing rewards`() {
        val source = readProjectFile("app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsViewModel.kt")
        val completeMission = source.substringAfter("fun completeMission(")
            .substringBefore("private fun startProfileCollector")

        assertTrue(completeMission.contains("repository.completeMission()"))
        assertFalse(completeMission.contains("MISSION_HONEY_REWARD"))
        assertFalse(completeMission.contains("newHoney"))
        assertFalse(completeMission.contains("streakBonusHoney ="))
        assertFalse(completeMission.contains("repository.updateProfile(updatedProfile)"))
    }

    @Test
    fun `sensitive profile state uses dedicated repository methods instead of direct profile patch`() {
        val repositorySource = readProjectFile("app/src/main/java/com/novahorizon/wanderly/data/WanderlyRepository.kt")
        val mapSource = readProjectFile("app/src/main/java/com/novahorizon/wanderly/ui/map/MapViewModel.kt")
        val mainSource = readProjectFile("app/src/main/java/com/novahorizon/wanderly/ui/main/MainViewModel.kt")

        assertTrue(repositorySource.contains("completeMission()"))
        assertTrue(repositorySource.contains("updateProfileLocation("))
        assertTrue(repositorySource.contains("acceptStreakLoss()"))
        assertTrue(repositorySource.contains("restoreStreak("))
        assertTrue(mapSource.contains("repository.updateProfileLocation(lat, lng)"))
        assertFalse(mapSource.contains("copy(last_lat = lat, last_lng = lng)"))
        assertTrue(mainSource.contains("repository.acceptStreakLoss()"))
        assertTrue(mainSource.contains("repository.restoreStreak(cost)"))
    }

    @Test
    fun `mission photo verification uses strict parser instead of broad yes substring`() {
        val source = readProjectFile("app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsViewModel.kt")
        val verifyPhoto = source.substringAfter("fun verifyPhoto(")
            .substringBefore("fun fetchPlaceDetails")

        assertTrue(verifyPhoto.contains("AiResponseParser.parsePhotoVerification("))
        assertTrue(verifyPhoto.contains("\"verified\""))
        assertFalse(verifyPhoto.contains("contains(\"YES\")"))
        assertFalse(verifyPhoto.contains(".uppercase()"))
    }

    private fun readProjectFile(path: String): String {
        val file = projectRoot().resolve(path)
        require(file.isFile) { "Missing required file: $path" }
        return file.readText()
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }
}
