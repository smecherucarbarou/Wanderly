package com.novahorizon.wanderly.data.mission

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.GeminiClient
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

interface GeminiCandidateTextClient {
    suspend fun generateCandidateJson(prompt: String, systemInstruction: String): String
}

object DefaultGeminiCandidateTextClient : GeminiCandidateTextClient {
    override suspend fun generateCandidateJson(prompt: String, systemInstruction: String): String {
        return GeminiClient.generateWithSearchText(
            prompt = prompt,
            systemInstruction = systemInstruction
        )
    }
}

class GeminiMissionCandidateProvider @Inject constructor(
    private val queryProvider: GeminiMissionQueryProvider
) : MissionCandidateProvider {
    constructor(textClient: GeminiCandidateTextClient) : this(
        GeminiMissionQueryProvider(
            object : GeminiQueryTextClient {
                override suspend fun generateQueryJson(prompt: String, systemInstruction: String): String {
                    return textClient.generateCandidateJson(prompt, systemInstruction)
                }
            }
        )
    )

    override suspend fun generateCandidates(
        city: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        missionType: String
    ): List<MissionPlaceCandidate> {
        return try {
            when (val result = queryProvider.generateQueries(city)) {
                is GeminiQueryResult.Success -> result.queries.mapIndexed { index, query ->
                    MissionPlaceCandidate(
                        name = query,
                        query = query,
                        category = "google_places_query",
                        reason = "Gemini search query hint; Google Places performs final validation.",
                        expectedCity = city,
                        priority = index + 1,
                        source = CandidateSource.GEMINI_QUERY_GOOGLE_PLACES
                    )
                }.also { candidates ->
                    logDebug("AI returned ${candidates.size} query candidates")
                }
                is GeminiQueryResult.Failure -> {
                    logDebug("AI query generation failed: ${result.error}")
                    emptyList()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Mission candidate provider failed; selector will use deterministic fallback", e)
            emptyList()
        }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            runCatching { AppLogger.d(TAG, LogRedactor.redact(message)) }
        }
    }

    private fun logWarning(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            runCatching {
                AppLogger.w(
                    TAG,
                    "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
                )
            }
        }
    }

    companion object {
        private const val TAG = "GeminiMissionCandidateProvider"
        internal const val SYSTEM_INSTRUCTION =
            "You are Wanderly's query generator. Return search query JSON only; Google Places selects final places."
    }
}
