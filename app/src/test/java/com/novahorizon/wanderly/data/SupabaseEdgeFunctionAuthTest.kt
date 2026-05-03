package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertFalse
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

    @Test
    fun `gemini proxy propagates status without raw upstream or internal detail`() {
        val source = projectFile("supabase/functions/gemini-proxy/index.ts").readText()

        assertTrue(source.contains("status: geminiResponse.status"))
        assertTrue(source.contains("upstream_status: geminiResponse.status"))
        assertTrue(source.contains("upstream_body: sanitizedUpstreamBody(responseText)"))
        assertTrue(source.contains("detail: \"Internal error\""))
        assertFalse(source.contains("upstream_body: responseText"))
        assertFalse(source.contains("detail: String(error)"))
        assertFalse(source.contains("console.error(`Gemini upstream error ${'$'}{geminiResponse.status}:`, responseText)"))
        assertFalse(source.contains("console.error(\"Proxy internal error:\", error)"))
        assertTrue(source.contains("}, 502)"))
        assertFalse(source.contains("model_unavailable_or_bad_endpoint"))
    }

    @Test
    fun `google places proxy propagates status without raw query upstream or internal detail`() {
        val source = projectFile("supabase/functions/google-places-proxy/index.ts").readText()

        assertTrue(source.contains("response.status"))
        assertTrue(source.contains("responseText"))
        assertTrue(source.contains("upstream_body"))
        assertTrue(source.contains("sanitizedUpstreamBody(responseText)"))
        assertTrue(source.contains("detail: \"Internal error\""))
        assertFalse(source.contains("query=\"${'$'}{body.textQuery.trim()}\""))
        assertFalse(source.contains("upstream_body: responseText"))
        assertFalse(source.contains("detail: String(error)"))
        assertFalse(source.contains("console.error(`Places upstream error ${'$'}{response.status}:`, responseText)"))
        assertFalse(source.contains("console.error(\"Proxy internal error:\", error)"))
        assertTrue(source.contains("}, 502)"))
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
        assertTrue(source.contains("upstream request failed") || source.contains("gemini_upstream_request_failed"))
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
