package com.novahorizon.wanderly.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.novahorizon.wanderly.BuildConfig
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
    private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent"
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
        val finalUrl = "$API_URL?key=${BuildConfig.GEMINI_API_KEY}"
        logDebug { "Starting Gemini $logLabel request with bodyLength=${body.toString().length}" }

        val request = Request.Builder()
            .url(finalUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Gemini $logLabel request failed with code=${response.code}")
                        logDebug { "Gemini $logLabel error bodyLength=${responseBody.length}" }
                        throw Exception("API call failed: ${response.code}")
                    }
                    logDebug { "Gemini $logLabel request succeeded with bodyLength=${responseBody.length}" }
                    extractText(responseBody)
                }
            } catch (e: Exception) {
                logException(e)
                throw e
            }
        }
    }

    private fun extractText(responseBody: String): String {
        val json = JSONObject(responseBody)
        val parts = json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")

        val textBuilder = StringBuilder()
        for (index in 0 until parts.length()) {
            val part = parts.getJSONObject(index)
            if (part.has("text")) {
                if (textBuilder.isNotEmpty()) textBuilder.append('\n')
                textBuilder.append(part.getString("text"))
            }
        }

        return textBuilder.toString().ifBlank {
            throw Exception("Empty Gemini response")
        }
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
