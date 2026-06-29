package com.novahorizon.wanderly.api

import com.novahorizon.wanderly.observability.AppLogger

import android.graphics.Bitmap
import android.util.Base64
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.util.await
import io.github.jan.supabase.auth.auth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object GeminiClient {
    private const val TAG = "GeminiClient"
    private const val API_URL = "/functions/v1/gemini-proxy"
    private const val MAX_ATTEMPTS = 3
    private val RETRYABLE_HTTP_CODES = setOf(429, 503)
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            require(req.url.isHttps) { "Non-HTTPS request blocked: ${req.url.host}" }
            chain.proceed(req)
        }
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateWithSearch(prompt: String): String {
        val body = buildTextBody(
            prompt = prompt,
            useSearch = true,
            systemInstruction = "You are a hyper-local scout. You MUST return ONLY raw JSON arrays. Do NOT include markdown blocks (```json), commentary, or any text before or after the JSON array."
        )
        return executeRequest(body, logLabel = "search")
    }

    suspend fun generateWithSearchText(prompt: String, systemInstruction: String? = null): String {
        val body = buildTextBody(
            prompt = prompt,
            useSearch = true,
            systemInstruction = systemInstruction
        )
        return executeRequest(body, logLabel = "search-text")
    }

    suspend fun generateText(prompt: String): String {
        val body = buildTextBody(prompt = prompt, useSearch = false)
        return executeRequest(body, logLabel = "text")
    }

    suspend fun analyzeImage(bitmap: Bitmap, prompt: String): String {
        val encodedImage = withContext(Dispatchers.IO) {
            val imageBytes = ByteArrayOutputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                outputStream.toByteArray()
            }
            Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        }
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", prompt) })
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", encodedImage)
                        })
                    })
                })
            }))
        }
        return executeRequest(body, logLabel = "vision")
    }

    suspend fun verifyMissionPhoto(
        bitmap: Bitmap,
        targetName: String,
        targetCity: String,
        isFallbackMission: Boolean
    ): String {
        val encodedImage = withContext(Dispatchers.IO) {
            val imageBytes = ByteArrayOutputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                outputStream.toByteArray()
            }
            Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        }
        val body = JSONObject().apply {
            put("mode", "mission_photo_verification")
            put("target_name", targetName)
            put("target_city", targetCity)
            put("is_fallback_mission", isFallbackMission)
            put("image_mime_type", "image/jpeg")
            put("image_data", encodedImage)
        }
        return executeRequest(body, logLabel = "mission-photo", extractGeminiText = false)
    }

    private fun buildTextBody(prompt: String, useSearch: Boolean, systemInstruction: String? = null): JSONObject {
        return JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", prompt)
                }))
            }))

            if (useSearch) {
                put("tools", JSONArray().put(JSONObject().apply {
                    put("google_search", JSONObject())
                }))
            }

            if (!systemInstruction.isNullOrBlank()) {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", systemInstruction)
                    }))
                })
            }
        }
    }

    private suspend fun executeRequest(
        body: JSONObject,
        logLabel: String,
        extractGeminiText: Boolean = true
    ): String {
        val auth = SupabaseClient.client.auth
        var accessToken = auth.currentAccessTokenOrNull()
            ?: throw Exception("Authentication required for Gemini proxy")

        val finalUrl = resolveProxyUrl()
        logDebug { "Starting Gemini $logLabel proxy request bodyLength=${body.toString().length}" }

        val buildRequest = { token: String ->
            Request.Builder()
                .url(finalUrl)
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }

        return withContext(Dispatchers.IO) {
            withRetry {
                try {
                    var request = buildRequest(accessToken)
                    var response = client.newCall(request).await()

                    // If 401, try to refresh the session once
                    if (response.code == 401) {
                        response.close()
                        AppLogger.w(TAG, "Gemini $logLabel got 401, attempting token refresh...")
                        try {
                            auth.refreshCurrentSession()
                            val newToken = auth.currentAccessTokenOrNull()
                            if (newToken != null && newToken != accessToken) {
                                accessToken = newToken
                                request = buildRequest(accessToken)
                                response = client.newCall(request).await()
                            } else {
                                throw Exception("Failed to refresh token or token unchanged")
                            }
                        } catch (refreshError: Exception) {
                            if (refreshError is CancellationException) throw refreshError
                            if (BuildConfig.DEBUG) {
                                AppLogger.e(TAG, "Session refresh failed: ${LogRedactor.redact(refreshError.message)}")
                            }
                            else AppLogger.e(TAG, "Session refresh failed [code=${refreshError.javaClass.simpleName}]")
                            throw GeminiHttpException(401, "Proxy call failed: 401 (Refresh failed)")
                        }
                    }

                    response.use { resp ->
                        val responseBody = resp.body.string()
                        if (!resp.isSuccessful) {
                            AppLogger.e(TAG, "Gemini $logLabel request failed with code=${resp.code}")
                            logDebug { "Gemini $logLabel error bodyLength=${responseBody.length}" }

                            val safeMessage = parseProxyErrorMessage(responseBody)?.take(ERROR_BODY_LOG_LIMIT)
                                ?: "Proxy call failed: ${resp.code}"

                            throw GeminiHttpException(resp.code, safeMessage)
                        }
                        parseStructuredError(responseBody)?.let { error ->
                            AppLogger.w(TAG, "Gemini $logLabel failed: type=${error.type} status=${error.status}")
                            throw GeminiHttpException(error.status ?: resp.code, error.message.take(ERROR_BODY_LOG_LIMIT))
                        }
                        logDebug { "Gemini $logLabel request succeeded with bodyLength=${responseBody.length}" }
                        if (extractGeminiText) {
                            extractText(responseBody).getOrElse { throw it }
                        } else {
                            responseBody
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logException(e)
                    throw e
                }
            }
        }
    }

    private data class StructuredGeminiError(
        val type: String?,
        val status: Int?,
        val message: String
    )

    private fun parseStructuredError(responseBody: String): StructuredGeminiError? {
        val json = runCatching { JSONObject(responseBody) }.getOrNull() ?: return null
        if (!json.has("ok") || json.optBoolean("ok", true)) return null
        val error = json.optJSONObject("error")
        return StructuredGeminiError(
            type = error?.optString("type")?.takeIf { it.isNotBlank() },
            status = error?.optInt("status")?.takeIf { it > 0 },
            message = error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: "Gemini proxy returned a structured error"
        )
    }

    private fun parseProxyErrorMessage(responseBody: String): String? {
        val json = runCatching { JSONObject(responseBody) }.getOrNull() ?: return null

        val directMessage = json.optString("message").takeIf { it.isNotBlank() }
        if (directMessage != null) return directMessage

        val nestedMessage = json.optJSONObject("error")
            ?.optString("message")
            ?.takeIf { it.isNotBlank() }

        return nestedMessage
    }

    private fun extractText(responseBody: String): Result<String> {
        val json = runCatching { JSONObject(responseBody) }.getOrElse {
            return Result.failure(IllegalStateException("Gemini returned malformed JSON."))
        }
        val candidates = json.optJSONArray("candidates")
            ?: return Result.failure(IllegalStateException("Gemini response did not include candidates."))
        val firstCandidate = candidates.optJSONObject(0)
            ?: return Result.failure(IllegalStateException("Gemini response did not include any candidates."))
        val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
            ?: return Result.failure(IllegalStateException("Gemini response did not include content parts."))

        val textBuilder = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val text = part.optString("text").takeIf { it.isNotBlank() } ?: continue
            if (textBuilder.isNotEmpty()) textBuilder.append('\n')
            textBuilder.append(text)
        }

        return textBuilder.toString()
            .takeIf { it.isNotBlank() }
            ?.let(Result.Companion::success)
            ?: Result.failure(IllegalStateException("Gemini response did not include any text parts."))
    }

    private inline fun logDebug(message: () -> String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, LogRedactor.redact(message()))
        }
    }

    private fun logException(e: Exception) {
        if (BuildConfig.DEBUG) {
            AppLogger.e(TAG, "Exception during Gemini call (${e.javaClass.simpleName}): ${LogRedactor.redact(e.message)}")
        } else {
            AppLogger.e(TAG, "Exception during Gemini call (${e.javaClass.simpleName})")
        }
    }

    internal suspend fun <T> withRetry(
        maxAttempts: Int = MAX_ATTEMPTS,
        initialDelayMs: Long = 500,
        jitterMs: (Long) -> Long = { delayMs -> Random.nextLong(delayMs / 2) },
        block: suspend () -> T
    ): T {
        var delayMs = initialDelayMs
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
                // Final attempt or a non-retryable failure: surface it (unified error semantics — the last
                // try now runs inside this catch instead of as a separate uncaught call).
                if (attempt == maxAttempts - 1 || !e.isRetryableGeminiFailure()) {
                    throw e
                }
                delay(delayMs + jitterMs(delayMs))
                delayMs *= 2
            }
        }
        throw lastError ?: IllegalStateException("withRetry exhausted without an attempt")
    }

    private fun Exception.isRetryableGeminiFailure(): Boolean {
        return when (this) {
            is GeminiHttpException -> code in RETRYABLE_HTTP_CODES
            // Transient transport failures (CancellationException is already handled before this call).
            is SocketTimeoutException -> true
            is IOException -> true
            else -> false
        }
    }

    sealed class GeminiClientException(
        message: String,
        cause: Throwable? = null
    ) : Exception(message, cause) {
        class Unauthorized(message: String = "Authentication required for Gemini.") :
            GeminiClientException(message)

        class QuotaExceeded(message: String = "Daily Gemini limit reached.") :
            GeminiClientException(message)

        class ServerError(code: Int, message: String) :
            GeminiClientException("Gemini proxy server error $code: $message")

        class HttpError(val code: Int, message: String) :
            GeminiClientException(message)

        class InvalidResponse(message: String, cause: Throwable? = null) :
            GeminiClientException(message, cause)
    }

    internal class GeminiHttpException(
        val code: Int,
        message: String
    ) : Exception(message)

    internal fun resolveProxyUrl(
        configuredProxyUrl: String = BuildConfig.GEMINI_PROXY_URL,
        supabaseUrl: String = BuildConfig.SUPABASE_URL
    ): String {
        return configuredProxyUrl.trim().ifBlank {
            "${supabaseUrl.trimEnd('/')}$API_URL"
        }
    }

    private const val ERROR_BODY_LOG_LIMIT = 512
}
