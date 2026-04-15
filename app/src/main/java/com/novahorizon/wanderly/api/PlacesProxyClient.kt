package com.novahorizon.wanderly.api

import com.novahorizon.wanderly.BuildConfig
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PlacesProxyClient {
    private const val API_URL = "/functions/v1/google-places-proxy"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun searchText(body: JSONObject, fieldMask: String): JSONObject = withContext(Dispatchers.IO) {
        val auth = SupabaseClient.client.auth
        var accessToken = auth.currentAccessTokenOrNull()
            ?: throw Exception("Authentication required for Places proxy")
        
        val finalUrl = "${BuildConfig.SUPABASE_URL.trimEnd('/')}$API_URL"
        val payload = JSONObject().apply {
            put("fieldMask", fieldMask)
            put("body", body)
        }

        val buildRequest = { token: String ->
            Request.Builder()
                .url(finalUrl)
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }

        var response = client.newCall(buildRequest(accessToken)).execute()

        if (response.code == 401) {
            response.close()
            try {
                auth.refreshCurrentSession()
                val newToken = auth.currentAccessTokenOrNull()
                if (newToken != null && newToken != accessToken) {
                    accessToken = newToken
                    response = client.newCall(buildRequest(accessToken)).execute()
                } else {
                    throw Exception("Failed to refresh token or token unchanged")
                }
            } catch (e: Exception) {
                throw Exception("Places proxy call failed: 401 (Refresh failed)")
            }
        }

        response.use { resp ->
            val responseBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw Exception("Places proxy call failed: ${resp.code}")
            }
            JSONObject(responseBody)
        }
    }
}
