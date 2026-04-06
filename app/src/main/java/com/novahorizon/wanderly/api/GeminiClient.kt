package com.novahorizon.wanderly.api

import com.google.ai.client.generativeai.GenerativeModel
import com.novahorizon.wanderly.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent"
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val model = GenerativeModel(
        modelName = "gemini-3-flash-preview",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    suspend fun generateWithSearch(prompt: String): String {
        val finalUrl = "$API_URL?key=${BuildConfig.GEMINI_API_KEY}"
        android.util.Log.d("GeminiClient", "Full URL: $finalUrl")
        android.util.Log.d("GeminiClient", "Prompt: ${prompt.take(200)}...")
        
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", prompt)
                }))
            }))
            put("tools", JSONArray().put(JSONObject().apply {
                put("google_search", JSONObject())
            }))
            put("system_instruction", JSONObject().apply {
                put("parts", JSONObject().apply {
                    put("text", "You are a hyper-local scout. You MUST return ONLY raw JSON arrays. Do NOT include markdown blocks (```json), commentary, or any text before or after the JSON array.")
                })
            })
        }

        val request = Request.Builder()
            .url(finalUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        android.util.Log.e("GeminiClient", "FAILURE! Code: ${response.code}")
                        android.util.Log.e("GeminiClient", "Error Body: $responseBody")
                        throw Exception("API call failed: ${response.code} - $responseBody")
                    }
                    android.util.Log.d("GeminiClient", "SUCCESS! Response: ${responseBody.take(100)}...")
                    val json = JSONObject(responseBody)
                    json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                }
            } catch (e: Exception) {
                android.util.Log.e("GeminiClient", "Exception during call", e)
                throw e
            }
        }
    }
}
