package com.novahorizon.wanderly.api

import com.novahorizon.wanderly.util.await
import io.github.jan.supabase.auth.Auth
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Sends the request from [buildRequest] (built with the current access token) and, on a 401,
 * refreshes the Supabase session once and retries with the new token. Returns the final, still-open
 * [Response] for the caller to map + close.
 *
 * Shared by the authed proxy clients (GeminiClient / PlacesProxyClient / DefaultAiAssistantGateway)
 * so the token + single-401-refresh + cancellation-aware await flow lives in one place — they had
 * already drifted (blocking execute vs await, differing retry), which this consolidates
 * (big_improvements C). Each caller keeps its own OkHttpClient, response mapping, and retry policy.
 */
internal suspend fun OkHttpClient.awaitWithTokenRefresh(
    auth: Auth,
    initialToken: String,
    buildRequest: (token: String) -> Request
): Response {
    var token = initialToken
    var response = newCall(buildRequest(token)).await()
    if (response.code == 401) {
        response.close()
        runCatching { auth.refreshCurrentSession() }
            .onFailure { if (it is CancellationException) throw it }
        val refreshed = auth.currentAccessTokenOrNull()
        if (!refreshed.isNullOrBlank() && refreshed != token) {
            token = refreshed
            response = newCall(buildRequest(token)).await()
        }
    }
    return response
}
