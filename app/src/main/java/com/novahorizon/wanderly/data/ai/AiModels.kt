package com.novahorizon.wanderly.data.ai

import java.time.Instant
import java.util.UUID

data class AiQuotaResult(
    val allowed: Boolean,
    val isPlus: Boolean,
    val used: Int,
    val limit: Int,
    val remaining: Int,
    val resetDate: String
)

data class AiGuideContext(
    val approximateLocation: String? = null,
    val currentCity: String? = null,
    val currentAdminArea: String? = null,
    val currentCountry: String? = null,
    val coarseCoordinates: String? = null,
    val travelPreferences: List<String> = emptyList(),
    val savedPlaces: List<String> = emptyList(),
    val tripContext: String? = null
) {
    fun isEmpty(): Boolean =
        approximateLocation.isNullOrBlank() &&
            currentCity.isNullOrBlank() &&
            currentAdminArea.isNullOrBlank() &&
            currentCountry.isNullOrBlank() &&
            coarseCoordinates.isNullOrBlank() &&
            travelPreferences.isEmpty() &&
            savedPlaces.isEmpty() &&
            tripContext.isNullOrBlank()
}

enum class AiChatRole(val wireValue: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system")
}

data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: AiChatRole,
    val content: String,
    val createdAt: Instant = Instant.now()
)

sealed class AiAssistantException(message: String) : Exception(message) {
    class Unauthenticated : AiAssistantException("Please sign in to use Wanderly Guide.")

    data class QuotaExceeded(val quota: AiQuotaResult) :
        AiAssistantException("You have reached today's Wanderly Guide limit.")

    class BackendError(message: String = SAFE_BACKEND_ERROR) : AiAssistantException(message)

    companion object {
        const val SAFE_BACKEND_ERROR = "Wanderly Guide is unavailable. Please try again."
    }
}
