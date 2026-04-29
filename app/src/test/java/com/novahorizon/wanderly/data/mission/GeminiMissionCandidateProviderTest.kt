package com.novahorizon.wanderly.data.mission

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiMissionCandidateProviderTest {

    @Test
    fun `builds hardened multi candidate prompt and parses response`() = runTest {
        val client = FakeGeminiCandidateTextClient(
            response = """
                {
                  "city": "Targu Jiu",
                  "country": "Romania",
                  "search_queries": [
                    "landmarks in Targu Jiu",
                    "public art in Targu Jiu"
                  ]
                }
            """.trimIndent()
        )
        val provider = GeminiMissionCandidateProvider(client)

        val candidates = provider.generateCandidates(
            city = "Targu Jiu",
            latitude = 45.034,
            longitude = 23.274,
            radiusKm = 5.0,
            missionType = "landmark"
        )

        assertEquals(2, candidates.size)
        assertEquals("landmarks in Targu Jiu", candidates.first().query)
        assertTrue(client.lastPrompt.contains("search query generator"))
        assertTrue(client.lastPrompt.contains("NOT allowed to invent place names"))
        assertTrue(client.lastPrompt.contains("ONLY output search queries"))
        assertTrue(client.lastPrompt.contains("City: Targu Jiu"))
        assertTrue(client.lastSystemInstruction.contains("search query JSON only"))
    }

    @Test
    fun `malformed provider response returns empty candidates`() = runTest {
        val provider = GeminiMissionCandidateProvider(
            FakeGeminiCandidateTextClient(response = "Sure, try the big monument nearby")
        )

        assertTrue(
            provider.generateCandidates(
                city = "Targu Jiu",
                latitude = 45.034,
                longitude = 23.274,
                radiusKm = 5.0,
                missionType = "landmark"
            ).isEmpty()
        )
    }

    private class FakeGeminiCandidateTextClient(
        private val response: String
    ) : GeminiCandidateTextClient {
        var lastPrompt: String = ""
            private set
        var lastSystemInstruction: String = ""
            private set

        override suspend fun generateCandidateJson(prompt: String, systemInstruction: String): String {
            lastPrompt = prompt
            lastSystemInstruction = systemInstruction
            return response
        }
    }
}
