package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseEdgeFunctionAuthTest {

    @Test
    fun `gemini proxy verifies bearer token with Supabase Auth`() {
        assertVerifiesJwt("supabase/functions/gemini-proxy/index.ts")
    }

    @Test
    fun `google places proxy verifies bearer token with Supabase Auth`() {
        assertVerifiesJwt("supabase/functions/google-places-proxy/index.ts")
    }

    private fun assertVerifiesJwt(relativePath: String) {
        val source = projectFile(relativePath).readText()

        assertTrue(source.contains("createClient"))
        assertTrue(source.contains("SUPABASE_URL"))
        assertTrue(source.contains("SUPABASE_ANON_KEY"))
        assertTrue(source.contains("verifyAuth"))
        assertTrue(source.contains("auth.getUser(token)"))
        assertTrue(source.contains("Invalid bearer token"))
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
