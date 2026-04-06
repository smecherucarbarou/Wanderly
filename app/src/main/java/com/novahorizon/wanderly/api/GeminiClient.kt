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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val model = GenerativeModel(
        modelName = "gemini-3-flash-preview",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    suspend fun generateWithSearch(prompt: String): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", prompt)
                }))
            }))
            put("tools", JSONArray().put(JSONObject().apply {
                put("google_search", JSONObject())
            }))
        }

        val request = Request.Builder()
            .url("$API_URL?key=${BuildConfig.GEMINI_API_KEY}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "no body"
                    throw Exception("API call failed: ${response.code} - $errorBody")
                }
                val responseBody = response.body?.string() ?: throw Exception("Empty response body")
                val json = JSONObject(responseBody)
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }
        }
    }
}