package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryArchitectureTest {

    @Test
    fun `ViewModels delegate auth operations to AuthRepository`() {
        val authViewModel = projectFile("app/src/main/java/com/novahorizon/wanderly/ui/auth/AuthViewModel.kt")
            .readText()
        val profileViewModel = projectFile("app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileViewModel.kt")
            .readText()

        assertHasNoSupabaseAuthAccess(authViewModel)
        assertHasNoSupabaseAuthAccess(profileViewModel)
        assertTrue(authViewModel.contains("AuthRepository"))
        assertTrue(profileViewModel.contains("AuthRepository"))
    }

    @Test
    fun `AuthRepository owns Supabase auth SDK calls`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/data/AuthRepository.kt")
            .readText()

        assertTrue(source.contains("class AuthRepository"))
        assertTrue(source.contains("signInWith"))
        assertTrue(source.contains("signUpWith"))
        assertTrue(source.contains("signOut"))
        assertTrue(source.contains("SupabaseClient.client.auth"))
    }

    private fun assertHasNoSupabaseAuthAccess(source: String) {
        assertFalse(source.contains("com.novahorizon.wanderly.api.SupabaseClient"))
        assertFalse(source.contains("io.github.jan.supabase.auth.auth"))
        assertFalse(source.contains("SupabaseClient.client.auth"))
        assertFalse(source.contains("signInWith("))
        assertFalse(source.contains("signUpWith("))
        assertFalse(source.contains("signOut()"))
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
