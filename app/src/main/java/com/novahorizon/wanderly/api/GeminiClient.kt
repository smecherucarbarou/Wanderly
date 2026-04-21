package com.novahorizon.wanderly.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.novahorizon.wanderly.BuildConfig
import io.github.jan.supabase.auth.auth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val TAG = "GeminiClient"
    private const val API_URL = "/functions/v1/gemini-proxy"
    private val client = OkHttpClient.Builder()
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
        val imageBytes = ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            outputStream.toByteArray()
        }
        val encodedImage = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
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

    private suspend fun executeRequest(body: JSONObject, logLabel: String): String {
        val auth = SupabaseClient.client.auth
        var accessToken = auth.currentAccessTokenOrNull()
            ?: throw Exception("Authentication required for Gemini proxy")

        val finalUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}$API_URL"
        logDebug { "Starting Gemini $logLabel request with bodyLength=${body.toString().length}" }

        val buildRequest = { token: String ->
            Request.Builder()
                .url(finalUrl)
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }

        return withContext(Dispatchers.IO) {
            try {
                var request = buildRequest(accessToken)
                var response = client.newCall(request).execute()

                // If 401, try to refresh the session once
                if (response.code == 401) {
                    response.close()
                    Log.w(TAG, "Gemini $logLabel got 401, attempting token refresh...")
                    try {
                        auth.refreshCurrentSession()
                        val newToken = auth.currentAccessTokenOrNull()
                        if (newToken != null && newToken != accessToken) {
                            accessToken = newToken
                            request = buildRequest(accessToken)
                            response = client.newCall(request).execute()
                        } else {
                            throw Exception("Failed to refresh token or token unchanged")
                        }
                    } catch (refreshError: Exception) {
                        Log.e(TAG, "Failed to refresh session: ${refreshError.message}")
                        throw Exception("Proxy call failed: 401 (Refresh failed)")
                    }
                }

                response.use { resp ->
                    val responseBody = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Gemini $logLabel request failed with code=${resp.code}")
                        logDebug { "Gemini $logLabel error bodyLength=${responseBody.length}" }
                        throw Exception("Proxy call failed: ${resp.code}")
                    }
                    logDebug { "Gemini $logLabel request succeeded with bodyLength=${responseBody.length}" }
                    extractText(responseBody).getOrElse { throw it }
                }
            } catch (e: Exception) {
                logException(e)
                throw e
            }
        }
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
            Log.d(TAG, message())
        }
    }

    private fun logException(e: Exception) {
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "Exception during Gemini call (${e.javaClass.simpleName})", e)
        } else {
            Log.e(TAG, "Exception during Gemini call (${e.javaClass.simpleName})")
        }
    }
}
