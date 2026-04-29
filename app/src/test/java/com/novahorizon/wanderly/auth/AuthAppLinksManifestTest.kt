package com.novahorizon.wanderly.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthAppLinksManifestTest {

    @Test
    fun `AuthActivity handles custom scheme auth callback`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val authActivity = manifest.substringAfter("""android:name=".AuthActivity"""")
            .substringBefore("</activity>")

        assertTrue(authActivity.contains("""<intent-filter>"""))
        assertTrue(authActivity.contains("""android:scheme="wanderly""""))
        assertTrue(authActivity.contains("""android:host="auth""""))
        assertTrue(authActivity.contains("""android:path="/callback""""))
        assertFalse(authActivity.contains("""android:scheme="https""""))
        assertFalse(authActivity.contains("""android:host="wanderly.app""""))
        assertFalse(authActivity.contains("""android:path="/auth/callback""""))
        assertFalse(authActivity.contains("""android:host="login""""))
    }

    @Test
    fun `auth constants point to custom scheme callback`() {
        val constants = projectFile("app/src/main/java/com/novahorizon/wanderly/Constants.kt")
            .readText()

        assertTrue(constants.contains("""const val AUTH_CALLBACK_SCHEME = "wanderly""""))
        assertTrue(constants.contains("""const val AUTH_CALLBACK_HOST = "auth""""))
        assertTrue(constants.contains("""const val AUTH_CALLBACK_PATH = "/callback""""))
        assertFalse(constants.contains("AUTH_CALLBACK_LEGACY_HOST"))
    }

    @Test
    fun `existing invite app links remain declared on SplashActivity`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val splashActivity = manifest.substringAfter("""android:name=".SplashActivity"""")
            .substringBefore("</activity>")

        assertTrue(splashActivity.contains("""android:scheme="wanderly""""))
        assertTrue(splashActivity.contains("""android:host="invite""""))
        assertTrue(splashActivity.contains("""android:pathPrefix="/invite/""""))
        assertTrue(splashActivity.contains("""android:host="wanderly.app""""))
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
