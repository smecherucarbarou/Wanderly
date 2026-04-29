package com.novahorizon.wanderly.data.mission

import kotlinx.serialization.Serializable

@Serializable
data class MissionPlaceCandidate(
    val name: String,
    val localName: String? = null,
    val query: String,
    val category: String? = null,
    val reason: String? = null,
    val expectedCity: String? = null,
    val priority: Int = 0,
    val placeId: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val address: String? = null,
    val city: String? = expectedCity,
    val types: List<String> = emptyList(),
    val rating: Double? = null,
    val userRatingsTotal: Int? = null,
    val openNow: Boolean? = null,
    val distanceMeters: Double = Double.POSITIVE_INFINITY,
    val source: CandidateSource = CandidateSource.GOOGLE_PLACES,
    val rejectionReason: String? = null,
    val score: Double = 0.0,
    val validationReason: String? = null,
    val rejectionLogs: List<MissionPlaceCandidate> = emptyList()
)

data class ValidatedMissionPlace(
    val originalCandidate: MissionPlaceCandidate,
    val placesName: String,
    val placesId: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val locality: String?,
    val formattedAddress: String?,
    val rating: Double?,
    val userRatingsTotal: Int?,
    val confidenceScore: Double,
    val source: CandidateSource = CandidateSource.GOOGLE_PLACES,
    val validationReason: String = "validated_google_places_candidate",
    val rejectionLogs: List<MissionPlaceCandidate> = emptyList()
)

sealed class MissionPlaceSelectionResult {
    data class Success(val place: ValidatedMissionPlace) : MissionPlaceSelectionResult()
    data class Fallback(val reason: String) : MissionPlaceSelectionResult()
}

sealed class MissionPlaceResult {
    data class Success(val place: MissionPlaceCandidate) : MissionPlaceResult()
    data class Error(val message: String) : MissionPlaceResult()
}

data class PlacesMissionSearchResult(
    val placesName: String,
    val placesId: String?,
    val latitude: Double,
    val longitude: Double,
    val locality: String?,
    val formattedAddress: String?,
    val rating: Double?,
    val userRatingsTotal: Int?,
    val types: Set<String>,
    val businessStatus: String?
)

data class MissionRadiusPolicy(
    val preferredMeters: Double,
    val hardMaxMeters: Double
)

interface MissionCandidateProvider {
    suspend fun generateCandidates(
        city: String,
        latitude: Double,
        longitude: Double,
        radiusKm: Double,
        missionType: String
    ): List<MissionPlaceCandidate>
}

interface MissionPlaceSearchService {
    suspend fun searchText(query: String): List<PlacesMissionSearchResult>
}

interface MissionPlaceSelecting {
    suspend fun selectBestMissionPlace(
        userLat: Double,
        userLng: Double,
        city: String,
        countryRegion: String?,
        missionType: String,
        candidates: List<MissionPlaceCandidate>
    ): MissionPlaceSelectionResult
}

enum class CandidateSource {
    GOOGLE_PLACES,
    GEMINI_QUERY_GOOGLE_PLACES,
    DETERMINISTIC_GOOGLE_PLACES,
    DETERMINISTIC_PLACES_FALLBACK,
    LOCAL_SAFE_FALLBACK
}

enum class AiProvider {
    GEMINI
}
