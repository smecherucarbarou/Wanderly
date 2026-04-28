package com.novahorizon.wanderly.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthAppLinksManifestTest {

    @Test
    fun `AuthActivity uses verified HTTPS app link for callbacks`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val authActivity = manifest.substringAfter("""android:name=".AuthActivity"""")
            .substringBefore("</activity>")

        assertTrue(authActivity.contains("""<intent-filter android:autoVerify="true">"""))
        assertTrue(authActivity.contains("""android:scheme="https""""))
        assertTrue(authActivity.contains("""android:host="wanderly.app""""))
        assertTrue(authActivity.contains("""android:path="/auth/callback""""))
        assertFalse(authActivity.contains("""android:scheme="wanderly""""))
        assertFalse(authActivity.contains("""android:host="auth""""))
        assertFalse(authActivity.contains("""android:host="login""""))
    }

    @Test
    fun `auth constants point to the verified HTTPS callback`() {
        val constants = projectFile("app/src/main/java/com/novahorizon/wanderly/Constants.kt")
            .readText()

        assertTrue(constants.contains("""const val AUTH_CALLBACK_SCHEME = "https""""))
        assertTrue(constants.contains("""const val AUTH_CALLBACK_HOST = "wanderly.app""""))
        assertTrue(constants.contains("""const val AUTH_CALLBACK_PATH = "/auth/callback""""))
        assertFalse(constants.contains("AUTH_CALLBACK_LEGACY_HOST"))
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
