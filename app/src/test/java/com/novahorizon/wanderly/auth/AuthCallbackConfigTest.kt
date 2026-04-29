package com.novahorizon.wanderly.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCallbackConfigTest {

    @Test
    fun `LoginFragment sends Google OAuth to custom scheme callback`() {
        val source = projectFile(
            "app/src/main/java/com/novahorizon/wanderly/ui/auth/LoginFragment.kt"
        ).readText()

        assertTrue(source.contains("redirectUrl = authCallbackUrl"))
        assertTrue(source.contains("Constants.AUTH_CALLBACK_SCHEME"))
        assertTrue(source.contains("Constants.AUTH_CALLBACK_HOST"))
        assertTrue(source.contains("Constants.AUTH_CALLBACK_PATH"))
        assertFalse(source.contains("https://wanderly.app/auth/callback"))
        assertFalse(source.contains("wanderly.app/auth/callback"))
    }

    @Test
    fun `Supabase auth plugin uses custom callback scheme and host`() {
        val source = projectFile(
            "app/src/main/java/com/novahorizon/wanderly/api/SupabaseClient.kt"
        ).readText()
        val authInstall = source.substringAfter("install(Auth)")
            .substringBefore("install(Realtime)")

        assertTrue(authInstall.contains("scheme = Constants.AUTH_CALLBACK_SCHEME"))
        assertTrue(authInstall.contains("host = Constants.AUTH_CALLBACK_HOST"))
        assertFalse(authInstall.contains("wanderly.app"))
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
