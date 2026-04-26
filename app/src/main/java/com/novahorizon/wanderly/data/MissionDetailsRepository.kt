package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.api.GeminiClient

open class MissionDetailsRepository(
    private val geminiClient: GeminiClient = GeminiClient
) {
    open suspend fun getPlaceDetails(placeName: String, targetCity: String): String {
        val prompt = """
            STRICT ACCURACY MODE with Google Search.
            Place: "$placeName" in the city of "$targetCity".
            Task: Provide a 3-sentence summary using real, up-to-date information.
            CRITICAL: Use Google Search to verify details about "$placeName" in "$targetCity".
            DO NOT mention venues from other cities.
            Include one unique fun fact discovered via search.
        """.trimIndent()

        return geminiClient.generateWithSearchText(
            prompt,
            systemInstruction = "You are a precise local travel guide. Return normal plain text only. Do not return JSON, markdown, bullet lists, or code fences."
        )
    }
}
