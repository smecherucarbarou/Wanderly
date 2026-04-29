package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutCoordinatorSourceTest {

    @Test
    fun `android logout coordinator factory clears app owned state and background work`() {
        val source = readProjectFile("app/src/main/java/com/novahorizon/wanderly/data/LogoutCoordinator.kt")

        assertTrue(source.contains("AuthRepository"))
        assertTrue(source.contains("HiveRealtimeService"))
        assertTrue(source.contains("WorkManager.getInstance"))
        assertTrue(source.contains("cancelUniqueWork(\"StreakCheckWork\")"))
        assertTrue(source.contains("cancelUniqueWork(\"SocialCheckWork\")"))
        assertTrue(source.contains("NotificationStateStore"))
        assertTrue(source.contains("repository.clearLocalState()"))
        assertTrue(source.contains("WanderlyStreakWidgetProvider.cancelScheduledUpdates"))
    }

    @Test
    fun `profile logout uses coordinator instead of ad hoc service and preference cleanup`() {
        val viewModel = readProjectFile("app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileViewModel.kt")
        val fragment = readProjectFile("app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt")

        assertTrue(viewModel.contains("LogoutCoordinator"))
        assertTrue(viewModel.contains("logoutCoordinator.logoutCompletely()"))
        assertFalse(viewModel.contains("repository.clearLocalState()"))
        assertFalse(viewModel.contains("repository.clearRememberMe()"))
        assertFalse(fragment.contains("stopService(Intent(requireContext(), HiveRealtimeService::class.java))"))
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
