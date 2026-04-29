package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiProxySourceTest {

    @Test
    fun `gemini proxy model is configurable with stable default`() {
        val source = projectFile("supabase/functions/gemini-proxy/index.ts").readText()

        assertTrue(source.contains("Deno.env.get(\"GEMINI_MODEL\")"))
        assertTrue(source.contains("gemini-3-flash-preview"))
        assertTrue(source.contains("gemini-2.5-flash"))
        assertFalse(source.contains(legacyGeminiModel("flash")))
        assertFalse(source.contains(legacyGeminiModel("pro")))
    }

    @Test
    fun `gemini proxy validates bounded body before quota and upstream call`() {
        val source = projectFile("supabase/functions/gemini-proxy/index.ts").readText()
        val validateIndex = source.indexOf("isValidGeminiPayload(payload)")
        val quotaIndex = source.indexOf("consumeApiQuota(req, auth, \"gemini\"")
        val upstreamIndex = source.indexOf("callGemini(geminiApiKey, geminiModel")

        assertTrue(source.contains("MAX_PROMPT_TEXT_CHARS"))
        assertTrue(source.contains("system_instruction"))
        assertTrue(source.contains("model_unavailable_or_bad_endpoint"))
        assertTrue(validateIndex >= 0)
        assertTrue(quotaIndex > validateIndex)
        assertTrue(upstreamIndex > quotaIndex)
    }

    private fun projectFile(relativePath: String): File {
        return File(projectRoot(), relativePath)
    }

    private fun legacyGeminiModel(suffix: String): String =
        "gemini-" + "1" + "." + "5-" + suffix

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }
}
