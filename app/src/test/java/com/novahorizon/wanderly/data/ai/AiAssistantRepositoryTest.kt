package com.novahorizon.wanderly.data.ai

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AiAssistantRepositoryTest {

    @Test
    fun `successful assistant response maps message and quota`() = runTest {
        val repository = AiAssistantRepository(
            gateway = FakeAiAssistantGateway(
                response = AiAssistantHttpResponse(
                    code = 200,
                    body = """
                        {
                          "message": "Start with a compact morning route and keep museums for the afternoon.",
                          "quota": {
                            "allowed": true,
                            "is_plus": false,
                            "used": 1,
                            "limit": 5,
                            "remaining": 4,
                            "reset_date": "2026-05-14"
                          }
                        }
                    """.trimIndent()
                )
            )
        )

        val result = repository.sendMessage(message = "Plan a 2-day trip")

        assertTrue(result.isSuccess)
        assertEquals(
            "Start with a compact morning route and keep museums for the afternoon.",
            result.getOrThrow()
        )
        assertEquals(
            AiQuotaResult(
                allowed = true,
                isPlus = false,
                used = 1,
                limit = 5,
                remaining = 4,
                resetDate = "2026-05-14"
            ),
            repository.latestQuota
        )
    }

    @Test
    fun `guide request includes approximate location context when available`() = runTest {
        val gateway = FakeAiAssistantGateway(
            response = AiAssistantHttpResponse(
                code = 200,
                body = """{"message":"Try lunch menus close to your current area."}"""
            )
        )
        val repository = AiAssistantRepository(gateway = gateway)

        val result = repository.sendMessage(
            message = "Find cheap food nearby",
            context = AiGuideContext(
                approximateLocation = "Bucharest area near 44.43, 26.10"
            )
        )

        assertTrue(result.isSuccess)
        val requestJson = org.json.JSONObject(gateway.lastBody)
        assertEquals(
            "Bucharest area near 44.43, 26.10",
            requestJson.getJSONObject("context").getString("approximate_location")
        )
    }

    @Test
    fun `assistant markdown emphasis is normalized for mobile chat`() = runTest {
        val repository = AiAssistantRepository(
            gateway = FakeAiAssistantGateway(
                response = AiAssistantHttpResponse(
                    code = 200,
                    body = """{"message":"**Cheap eats nearby**\n- **Lunch menus** are usually the best value."}"""
                )
            )
        )

        val result = repository.sendMessage(message = "Find cheap food nearby")

        assertTrue(result.isSuccess)
        assertEquals(
            "Cheap eats nearby\n- Lunch menus are usually the best value.",
            result.getOrThrow()
        )
    }

    @Test
    fun `quota exceeded response maps to typed error`() = runTest {
        val repository = AiAssistantRepository(
            gateway = FakeAiAssistantGateway(
                response = AiAssistantHttpResponse(
                    code = 429,
                    body = """
                        {
                          "error": "quota_exceeded",
                          "quota": {
                            "allowed": false,
                            "is_plus": false,
                            "used": 5,
                            "limit": 5,
                            "remaining": 0,
                            "reset_date": "2026-05-14"
                          }
                        }
                    """.trimIndent()
                )
            )
        )

        val result = repository.sendMessage(message = "Hidden gems around me")
        val error = result.exceptionOrNull()

        assertTrue(error is AiAssistantException.QuotaExceeded)
        assertEquals(5, (error as AiAssistantException.QuotaExceeded).quota.limit)
        assertEquals(0, error.quota.remaining)
    }

    @Test
    fun `generic backend error maps to safe error`() = runTest {
        val repository = AiAssistantRepository(
            gateway = FakeAiAssistantGateway(
                response = AiAssistantHttpResponse(
                    code = 502,
                    body = """{"error":{"message":"raw upstream stack trace should not leak"}}"""
                )
            )
        )

        val result = repository.sendMessage(message = "Cheap food nearby")
        val error = result.exceptionOrNull()

        assertTrue(error is AiAssistantException.BackendError)
        assertEquals(
            "Wanderly Guide is unavailable. Please try again.",
            error?.message
        )
    }

    private class FakeAiAssistantGateway(
        private val response: AiAssistantHttpResponse,
        private val authenticated: Boolean = true
    ) : AiAssistantGateway {
        var lastBody: String = ""
            private set

        override suspend fun hasAuthenticatedSession(): Boolean = authenticated

        override suspend fun postGuideRequest(body: String): AiAssistantHttpResponse {
            lastBody = body
            return response
        }
    }
}
