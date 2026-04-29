package com.novahorizon.wanderly.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class OverpassDataSourceTimeoutTest {

    @Test
    fun `default request timeout is demo-safe`() {
        assertTrue(OverpassDataSource.DEFAULT_REQUEST_TIMEOUT_MS >= 5_000L)
    }

    @Test
    fun `timeout returns empty results instead of throwing`() = runTest {
        val callFactory = NeverCompletingCallFactory()
        val dataSource = OverpassDataSource(
            callFactory = callFactory,
            endpoints = listOf("https://overpass.test/api"),
            requestTimeoutMs = 10L,
            maxAttempts = 1,
            logWarnings = false
        )

        val result = dataSource.fetchNearbyPlaceNames(44.0, 26.0, 500)

        assertEquals(emptyList<String>(), result)
        assertEquals(1, callFactory.callCount)
        assertTrue(callFactory.lastCallCancelled)
    }

    @Test
    fun `failures use bounded endpoint retries`() = runTest {
        val callFactory = FailingCallFactory(IOException("socket timeout"))
        val dataSource = OverpassDataSource(
            callFactory = callFactory,
            endpoints = listOf(
                "https://overpass-one.test/api",
                "https://overpass-two.test/api",
                "https://overpass-three.test/api"
            ),
            requestTimeoutMs = 50L,
            maxAttempts = 2,
            logWarnings = false
        )

        val result = dataSource.fetchHiddenGemCandidates(44.0, 26.0, 500)

        assertEquals(emptyList<DiscoveredPlace>(), result)
        assertEquals(2, callFactory.callCount)
    }

    private class NeverCompletingCallFactory : Call.Factory {
        var callCount = 0
            private set
        var lastCallCancelled = false
            private set

        override fun newCall(request: Request): Call {
            callCount++
            return TestCall(request) {
                onCancel = { lastCallCancelled = true }
            }
        }
    }

    private class FailingCallFactory(private val error: IOException) : Call.Factory {
        var callCount = 0
            private set

        override fun newCall(request: Request): Call {
            callCount++
            return TestCall(request) {
                onEnqueue = { call, callback -> callback.onFailure(call, error) }
            }
        }
    }

    private class TestCall(
        private val request: Request,
        configure: TestCall.() -> Unit
    ) : Call {
        var onEnqueue: (Call, Callback) -> Unit = { _, _ -> }
        var onCancel: () -> Unit = {}
        private var canceled = false

        init {
            configure()
        }

        override fun request(): Request = request
        override fun execute(): Response = response(request, "")
        override fun enqueue(responseCallback: Callback) = onEnqueue(this, responseCallback)
        override fun cancel() {
            canceled = true
            onCancel()
        }
        override fun isExecuted(): Boolean = false
        override fun isCanceled(): Boolean = canceled
        override fun clone(): Call = TestCall(request) {
            onEnqueue = this@TestCall.onEnqueue
            onCancel = this@TestCall.onCancel
        }
        override fun timeout(): Timeout = Timeout().timeout(1, TimeUnit.SECONDS)
        override fun <T : Any> tag(type: KClass<T>): T? = null
        override fun <T> tag(type: Class<out T>): T? = null
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = computeIfAbsent()
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = computeIfAbsent()

        private fun response(request: Request, body: String): Response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .headers(Headers.headersOf())
                .build()
    }
}
