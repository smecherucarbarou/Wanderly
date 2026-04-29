package com.novahorizon.wanderly.data.mission

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.GeminiClient
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

interface GeminiQueryTextClient {
    suspend fun generateQueryJson(prompt: String, systemInstruction: String): String
}

object DefaultGeminiQueryTextClient : GeminiQueryTextClient {
    override suspend fun generateQueryJson(prompt: String, systemInstruction: String): String {
        return GeminiClient.generateWithSearchText(prompt, systemInstruction)
    }
}

sealed class GeminiQueryResult {
    data class Success(val queries: List<String>) : GeminiQueryResult()
    data class Failure(val error: String) : GeminiQueryResult()
}

class GeminiMissionQueryProvider @Inject constructor(
    private val textClient: GeminiQueryTextClient
) {
    suspend fun generateQueries(city: String, country: String = "Romania"): GeminiQueryResult {
        return try {
            val response = textClient.generateQueryJson(
                prompt = buildPrompt(city, country),
                systemInstruction = SYSTEM_INSTRUCTION
            )
            val queries = parseQueries(response)
            if (queries.isEmpty()) {
                GeminiQueryResult.Failure("Gemini returned no search queries")
            } else {
                GeminiQueryResult.Success(queries)
            }
        } catch (e: Exception) {
            logWarning("Gemini query generation failed", e)
            GeminiQueryResult.Failure(e.message ?: "Gemini query generation failed")
        }
    }

    private fun buildPrompt(city: String, country: String): String {
        val safeCity = city.trim().ifBlank { "the user's current city" }
        return """
            You are a search query generator for a mobile exploration app.

            STRICT RULES:
            - You are NOT allowed to invent place names.
            - You are NOT allowed to output final mission locations.
            - You ONLY output search queries and category hints for Google Places API.
            - The Android app validates ALL results through Google Places.
            - Return ONLY valid JSON. No markdown. No prose. No commentary. No backticks.

            Generate 10-12 distinct, specific search queries for Google Places Text Search
            targeting the city provided. Vary query types: landmarks, parks, museums,
            public art, scenic viewpoints, hidden gems, cultural sites.

            City: $safeCity
            Country: $country

            Expected JSON schema:
            {
              "city": "string",
              "country": "string",
              "search_queries": ["string"],
              "allowed_place_types": ["tourist_attraction", "park", "museum", "art_gallery", "point_of_interest", "establishment"],
              "blocked_place_types": ["school", "primary_school", "secondary_school", "university", "hospital", "doctor", "pharmacy", "police", "courthouse", "local_government_office", "lodging", "bar", "night_club", "casino", "church", "cemetery", "funeral_home"]
            }
        """.trimIndent()
    }

    private fun parseQueries(raw: String): List<String> {
        val jsonText = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = Json.parseToJsonElement(jsonText).jsonObject
        val array = json["search_queries"]?.jsonArray ?: return emptyList()
        val seen = linkedSetOf<String>()
        for (element in array) {
            val query = element.jsonPrimitive.content.trim().replace(Regex("\\s+"), " ")
            if (query.isNotBlank()) {
                seen += query
            }
        }
        return seen.distinctBy { it.lowercase() }.take(MAX_GEMINI_QUERIES)
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
        private const val TAG = "GeminiMissionQueryProvider"
        private const val MAX_GEMINI_QUERIES = 20
        private const val SYSTEM_INSTRUCTION =
            "Return search query JSON only. Never return final mission places."
    }
}
