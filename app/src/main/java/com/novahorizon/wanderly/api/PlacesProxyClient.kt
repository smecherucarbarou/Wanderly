package com.novahorizon.wanderly.api

import com.novahorizon.wanderly.BuildConfig
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object PlacesProxyClient {
    private const val API_URL = "/functions/v1/google-places-proxy"
    private const val ERROR_BODY_LOG_LIMIT = 512
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            require(req.url.isHttps) { "Non-HTTPS request blocked: ${req.url.host}" }
            chain.proceed(req)
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun searchText(body: JSONObject, fieldMask: String): NetworkResult<JSONObject> = withContext(Dispatchers.IO) {
        val auth = SupabaseClient.client.auth
        val accessToken = auth.currentAccessTokenOrNull()
            ?: return@withContext NetworkResult.HttpError(401, "Authentication required for Places proxy")
        
        val finalUrl = resolveProxyUrl()
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

        try {
            val response = client.awaitWithTokenRefresh(auth, accessToken, buildRequest)

            response.use { resp ->
                val responseBody = resp.body.string()
                if (!resp.isSuccessful) {
                    val safeMessage = parseProxyErrorMessage(responseBody)?.take(ERROR_BODY_LOG_LIMIT)
                        ?: "Places proxy failed: ${resp.code}"
                    return@withContext NetworkResult.HttpError(resp.code, safeMessage)
                }
                runCatching { JSONObject(responseBody) }
                    .fold(
                        onSuccess = { NetworkResult.Success(it) },
                        onFailure = { NetworkResult.ParseError(it as? Exception ?: RuntimeException(it)) }
                    )
            }
        } catch (_: SocketTimeoutException) {
            NetworkResult.Timeout
        } catch (e: IOException) {
            NetworkResult.NetworkError(e)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            NetworkResult.ParseError(e)
        }
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

    internal fun resolveProxyUrl(
        configuredProxyUrl: String = BuildConfig.PLACES_PROXY_URL,
        supabaseUrl: String = BuildConfig.SUPABASE_URL
    ): String {
        return configuredProxyUrl.trim().ifBlank {
            "${supabaseUrl.trimEnd('/')}$API_URL"
        }
    }
}
