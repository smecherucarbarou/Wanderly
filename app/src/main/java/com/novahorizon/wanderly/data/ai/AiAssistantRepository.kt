package com.novahorizon.wanderly.data.ai

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.util.await
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class AiAssistantHttpResponse(
    val code: Int,
    val body: String
)

interface AiAssistantGateway {
    suspend fun hasAuthenticatedSession(): Boolean
    suspend fun postGuideRequest(body: String): AiAssistantHttpResponse
}

class DefaultAiAssistantGateway @Inject constructor() : AiAssistantGateway {
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            require(request.url.isHttps) { "Non-HTTPS request blocked: ${request.url.host}" }
            chain.proceed(request)
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun hasAuthenticatedSession(): Boolean {
        return SupabaseClient.client.auth.currentAccessTokenOrNull() != null ||
            AuthSessionCoordinator.awaitResolvedSessionOrNull() != null
    }

    override suspend fun postGuideRequest(body: String): AiAssistantHttpResponse = withContext(Dispatchers.IO) {
        val auth = SupabaseClient.client.auth
        var accessToken = auth.currentAccessTokenOrNull()
            ?: throw AiAssistantException.Unauthenticated()

        val requestBody = body.toRequestBody("application/json".toMediaType())
        val buildRequest = { token: String ->
            Request.Builder()
                .url(resolveProxyUrl())
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()
        }

        var response = client.newCall(buildRequest(accessToken)).await()
        if (response.code == 401) {
            response.close()
            runCatching { auth.refreshCurrentSession() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logWarning("Guide token refresh failed", error)
                }
            val refreshedToken = auth.currentAccessTokenOrNull()
            if (!refreshedToken.isNullOrBlank() && refreshedToken != accessToken) {
                accessToken = refreshedToken
                response = client.newCall(buildRequest(accessToken)).await()
            }
        }

        response.use { resp ->
            AiAssistantHttpResponse(
                code = resp.code,
                body = resp.body.string()
            )
        }
    }

    private fun resolveProxyUrl(): String =
        BuildConfig.GEMINI_PROXY_URL.trim().ifBlank {
            "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/gemini-proxy"
        }

    private fun logWarning(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            AppLogger.w(
                TAG,
                "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
            )
        }
    }

    private companion object {
        const val TAG = "AiAssistantGateway"
    }
}

open class AiAssistantRepository @Inject constructor(
    private val gateway: AiAssistantGateway
) {
    constructor() : this(NoopAiAssistantGateway)

    @Volatile
    var latestQuota: AiQuotaResult? = null
        private set

    open suspend fun isAuthenticated(): Boolean = gateway.hasAuthenticatedSession()

    open suspend fun sendMessage(
        message: String,
        history: List<AiChatMessage> = emptyList(),
        context: AiGuideContext = AiGuideContext()
    ): Result<String> {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isBlank()) {
            return Result.failure(AiAssistantException.BackendError())
        }

        return try {
            if (!gateway.hasAuthenticatedSession()) {
                return Result.failure(AiAssistantException.Unauthenticated())
            }

            val response = gateway.postGuideRequest(buildRequestBody(trimmedMessage, history, context))
            parseResponse(response)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            when (e) {
                is AiAssistantException -> Result.failure(e)
                else -> {
                    logError("Guide request failed", e)
                    Result.failure(AiAssistantException.BackendError())
                }
            }
        }
    }

    private fun parseResponse(response: AiAssistantHttpResponse): Result<String> {
        val json = runCatching { JSONObject(response.body) }.getOrNull()

        if (response.code == 401 || response.code == 403) {
            return Result.failure(AiAssistantException.Unauthenticated())
        }

        if (response.code == 429 && json?.optString("error") == "quota_exceeded") {
            val quota = parseQuota(json.optJSONObject("quota"))
                ?: AiQuotaResult(
                    allowed = false,
                    isPlus = false,
                    used = 0,
                    limit = 0,
                    remaining = 0,
                    resetDate = ""
                )
            latestQuota = quota
            return Result.failure(AiAssistantException.QuotaExceeded(quota))
        }

        if (response.code !in 200..299 || json == null) {
            return Result.failure(AiAssistantException.BackendError())
        }

        val quota = parseQuota(json.optJSONObject("quota"))
        latestQuota = quota ?: latestQuota

        val assistantMessage = json.optString("message")
            .trim()
            .normalizeAssistantText()
            .takeIf { it.isNotBlank() }
            ?: return Result.failure(AiAssistantException.BackendError())

        return Result.success(assistantMessage)
    }

    private fun buildRequestBody(
        message: String,
        history: List<AiChatMessage>,
        context: AiGuideContext
    ): String {
        val historyJson = JSONArray()
        history.takeLast(MAX_HISTORY_MESSAGES).forEach { chatMessage ->
            historyJson.put(JSONObject().apply {
                put("role", chatMessage.role.wireValue)
                put("content", chatMessage.content.take(MAX_MESSAGE_CHARS))
                put("created_at", chatMessage.createdAt.toString())
            })
        }

        return JSONObject().apply {
            put("mode", "wanderly_guide")
            put("message", message.take(MAX_MESSAGE_CHARS))
            put("history", historyJson)
            if (!context.isEmpty()) {
                put("context", context.toJson())
            }
        }.toString()
    }

    private fun AiGuideContext.toJson(): JSONObject =
        JSONObject().apply {
            approximateLocation?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("approximate_location", it.take(MAX_CONTEXT_VALUE_CHARS))
            }
            currentCity?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("current_city", it.take(MAX_CONTEXT_VALUE_CHARS))
            }
            currentAdminArea?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("current_admin_area", it.take(MAX_CONTEXT_VALUE_CHARS))
            }
            currentCountry?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("current_country", it.take(MAX_CONTEXT_VALUE_CHARS))
            }
            coarseCoordinates?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("coarse_coordinates", it.take(MAX_CONTEXT_VALUE_CHARS))
            }
            if (travelPreferences.isNotEmpty()) {
                put("travel_preferences", travelPreferences.toJsonArray())
            }
            if (savedPlaces.isNotEmpty()) {
                put("saved_places", savedPlaces.toJsonArray())
            }
            tripContext?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("trip_context", it.take(MAX_CONTEXT_VALUE_CHARS))
            }
        }

    private fun List<String>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            take(MAX_CONTEXT_ARRAY_ITEMS)
                .map { it.trim().take(MAX_CONTEXT_VALUE_CHARS) }
                .filter { it.isNotBlank() }
                .forEach(array::put)
        }

    private fun String.normalizeAssistantText(): String =
        replace(Regex("""(?m)^\s{0,3}#{1,6}\s+"""), "")
            .replace(Regex("""\*\*([^*\n]+?)\*\*"""), "$1")
            .replace(Regex("""__([^_\n]+?)__"""), "$1")
            .replace(Regex("""`([^`\n]+?)`"""), "$1")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()

    private fun parseQuota(json: JSONObject?): AiQuotaResult? {
        json ?: return null
        return AiQuotaResult(
            allowed = json.optBoolean("allowed", false),
            isPlus = json.optBoolean("is_plus", false),
            used = json.optInt("used", 0),
            limit = json.optInt("limit", 0),
            remaining = json.optInt("remaining", 0),
            resetDate = json.optString("reset_date", "")
        )
    }

    private fun logError(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            AppLogger.e(
                TAG,
                "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
            )
        } else {
            AppLogger.e(TAG, message)
        }
    }

    private object NoopAiAssistantGateway : AiAssistantGateway {
        override suspend fun hasAuthenticatedSession(): Boolean = false

        override suspend fun postGuideRequest(body: String): AiAssistantHttpResponse {
            throw AiAssistantException.Unauthenticated()
        }
    }

    companion object {
        private const val TAG = "AiAssistantRepository"
        private const val MAX_MESSAGE_CHARS = 4_000
        private const val MAX_HISTORY_MESSAGES = 12
        private const val MAX_CONTEXT_VALUE_CHARS = 1_000
        private const val MAX_CONTEXT_ARRAY_ITEMS = 8
    }
}
