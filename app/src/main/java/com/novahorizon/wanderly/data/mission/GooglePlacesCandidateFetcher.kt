package com.novahorizon.wanderly.data.mission

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

open class GooglePlacesCandidateFetcher @Inject constructor(
    private val searchService: MissionPlaceSearchService
) {
    open suspend fun fetchAllCandidates(
        queries: List<String>,
        userLat: Double,
        userLng: Double,
        radius: Int = HARD_RADIUS,
        source: CandidateSource = CandidateSource.GOOGLE_PLACES
    ): List<MissionPlaceCandidate> {
        val allRaw = mutableListOf<MissionPlaceCandidate>()
        queries.forEachIndexed { index, query ->
            logDebug("Places query[${index + 1}/${queries.size}]=\"$query\" radius=$radius")
            val results = try {
                if (searchService is GooglePlacesSearchService) {
                    when (val result = searchService.searchTextResult(query, userLat, userLng, radius)) {
                        is PlacesSearchResult.Success -> result.places
                        is PlacesSearchResult.Error -> emptyList()
                    }
                } else if (searchService is GooglePlacesMissionPlaceSearchService) {
                    searchService.searchText(query, userLat, userLng, radius)
                } else {
                    searchService.searchText(query)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logWarning("Places query failed query=\"$query\"", e)
                emptyList()
            }
            logDebug("Places query result count=${results.size}")
            allRaw += results.map { place ->
                place.toMissionPlaceCandidate(
                    query = query,
                    source = source,
                    userLat = userLat,
                    userLng = userLng
                )
            }
        }
        logDebug("Raw candidates=${allRaw.size}")
        return allRaw
    }

    private fun PlacesMissionSearchResult.toMissionPlaceCandidate(
        query: String,
        source: CandidateSource,
        userLat: Double,
        userLng: Double
    ): MissionPlaceCandidate {
        val distance = distanceMeters(userLat, userLng, latitude, longitude)
        return MissionPlaceCandidate(
            placeId = placesId,
            name = placesName,
            lat = latitude,
            lng = longitude,
            address = formattedAddress,
            city = locality,
            types = types.toList(),
            rating = rating,
            userRatingsTotal = userRatingsTotal,
            openNow = null,
            distanceMeters = distance,
            source = source,
            query = query,
            category = primarySafeCategory(types),
            expectedCity = locality
        )
    }

    private fun primarySafeCategory(types: Set<String>): String? =
        when {
            "museum" in types -> "museum"
            "park" in types -> "park"
            "art_gallery" in types -> "public art"
            "tourist_attraction" in types -> "tourist_attraction"
            else -> null
        }

    private fun distanceMeters(
        userLat: Double,
        userLng: Double,
        placeLat: Double,
        placeLng: Double
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(placeLat - userLat)
        val dLng = Math.toRadians(placeLng - userLng)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(userLat)) *
            cos(Math.toRadians(placeLat)) *
            sin(dLng / 2).let { it * it }
        return earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            runCatching { AppLogger.d(TAG, LogRedactor.redact(message)) }
        }
    }

    private fun logWarning(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            runCatching {
                AppLogger.w(TAG, "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]")
            }
        }
    }

    companion object {
        private const val TAG = "MissionPlaceSelector"
        const val PREFERRED_RADIUS = 3_000
        const val SOFT_RADIUS = 5_000
        const val HARD_RADIUS = 8_000
        const val EMERGENCY_RADIUS = 12_000
    }
}
