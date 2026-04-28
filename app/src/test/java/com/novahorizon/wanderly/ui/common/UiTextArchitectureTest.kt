package com.novahorizon.wanderly.ui.common

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextArchitectureTest {

    @Test
    fun `ViewModels expose UI text without resolving Android resources`() {
        val viewModelSources = listOf(
            "app/src/main/java/com/novahorizon/wanderly/ui/gems/GemsViewModel.kt",
            "app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsViewModel.kt",
            "app/src/main/java/com/novahorizon/wanderly/ui/profile/AdminToolsViewModel.kt",
            "app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileViewModel.kt",
            "app/src/main/java/com/novahorizon/wanderly/ui/social/SocialViewModel.kt"
        ).map { projectFile(it).readText() }

        val violations = viewModelSources.filter { source ->
            source.contains("repository.context.getString") ||
                source.contains("import androidx.annotation.StringRes") ||
                source.contains("messageRes")
        }

        assertTrue("ViewModels still resolve or expose resource IDs directly", violations.isEmpty())
        assertTrue(viewModelSources.all { it.contains("UiText") })
    }

    @Test
    fun `UiText supports resource and dynamic messages`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/ui/common/UiText.kt")
            .readText()

        assertTrue(source.contains("sealed class UiText"))
        assertTrue(source.contains("data class StringResource"))
        assertTrue(source.contains("data class DynamicString"))
        assertTrue(source.contains("fun asString"))
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
