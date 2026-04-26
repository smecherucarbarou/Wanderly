package com.novahorizon.wanderly.api

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HttpRetryTest {

    @Test
    fun `429 response retries three attempts with exponential delay`() = runTest {
        var attempts = 0

        val error = expectGeminiHttpException {
            GeminiClient.withRetry(initialDelayMs = 100, jitterMs = { 0L }) {
                attempts++
                throw GeminiClient.GeminiHttpException(429, "rate limited")
            }
        }

        assertEquals(429, error.code)
        assertEquals(3, attempts)
        assertEquals(300L, currentTime)
    }

    @Test
    fun `500 response is retried`() = runTest {
        var attempts = 0

        val error = expectGeminiHttpException {
            GeminiClient.withRetry(initialDelayMs = 100, jitterMs = { 0L }) {
                attempts++
                throw GeminiClient.GeminiHttpException(500, "server error")
            }
        }

        assertEquals(500, error.code)
        assertEquals(3, attempts)
    }

    @Test
    fun `400 response is not retried`() = runTest {
        var attempts = 0

        val error = expectGeminiHttpException {
            GeminiClient.withRetry(initialDelayMs = 100, jitterMs = { 0L }) {
                attempts++
                throw GeminiClient.GeminiHttpException(400, "bad request")
            }
        }

        assertEquals(400, error.code)
        assertEquals(1, attempts)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `success on third attempt returns result`() = runTest {
        var attempts = 0

        val result = GeminiClient.withRetry(initialDelayMs = 100, jitterMs = { 0L }) {
            attempts++
            if (attempts < 3) {
                throw GeminiClient.GeminiHttpException(503, "temporarily unavailable")
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
        assertEquals(300L, currentTime)
    }

    private suspend fun expectGeminiHttpException(
        block: suspend () -> Unit
    ): GeminiClient.GeminiHttpException {
        try {
            block()
            fail("Expected GeminiHttpException")
        } catch (e: GeminiClient.GeminiHttpException) {
            return e
        }
        throw AssertionError("Unreachable")
    }
}
