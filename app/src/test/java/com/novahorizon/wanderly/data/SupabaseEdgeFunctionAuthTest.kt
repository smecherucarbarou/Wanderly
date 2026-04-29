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

    @Test
    fun `gemini proxy consumes user quota before upstream fetch`() {
        assertQuotaBeforeProviderFetch(
            relativePath = "supabase/functions/gemini-proxy/index.ts",
            provider = "gemini",
            upstream = "generativelanguage.googleapis.com"
        )
    }

    @Test
    fun `google places proxy consumes user quota before upstream fetch`() {
        assertQuotaBeforeProviderFetch(
            relativePath = "supabase/functions/google-places-proxy/index.ts",
            provider = "places",
            upstream = "places.googleapis.com"
        )
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

    private fun assertQuotaBeforeProviderFetch(relativePath: String, provider: String, upstream: String) {
        val source = projectFile(relativePath).readText()
        val quotaIndex = source.indexOf("consumeApiQuota(req, auth, \"$provider\"")
        val exhaustedIndex = source.indexOf("Quota exhausted")
        val upstreamFetchIndex = if (provider == "gemini") {
            source.indexOf("callGemini(geminiApiKey, geminiModel")
        } else {
            source.indexOf(upstream)
        }

        assertTrue(source.contains("consume_api_quota"))
        assertTrue(source.contains("return jsonResponse(req, { error: \"Quota exhausted\" }, 429)"))
        assertTrue(source.contains("maxRequestsPerDay"))
        assertTrue(quotaIndex >= 0)
        assertTrue(exhaustedIndex > quotaIndex)
        assertTrue(upstreamFetchIndex > quotaIndex)
        assertTrue(source.contains("upstream request failed") || source.contains("model_unavailable_or_bad_endpoint"))
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
