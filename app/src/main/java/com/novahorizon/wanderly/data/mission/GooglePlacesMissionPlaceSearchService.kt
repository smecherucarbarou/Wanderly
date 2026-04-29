package com.novahorizon.wanderly.data.mission

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.NetworkResult
import com.novahorizon.wanderly.api.PlacesProxyClient
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class GooglePlacesMissionPlaceSearchService : MissionPlaceSearchService {
    override suspend fun searchText(query: String): List<PlacesMissionSearchResult> {
        return searchText(query, locationLat = null, locationLng = null, radiusMeters = null)
    }

    suspend fun searchText(
        query: String,
        locationLat: Double?,
        locationLng: Double?,
        radiusMeters: Int?
    ): List<PlacesMissionSearchResult> {
        return try {
            val body = JSONObject().apply {
                put("textQuery", query)
                put("languageCode", "ro")
                put("regionCode", "RO")
                if (locationLat != null && locationLng != null && radiusMeters != null) {
                    put("locationBias", JSONObject().apply {
                        put("circle", JSONObject().apply {
                            put("center", JSONObject().apply {
                                put("latitude", locationLat)
                                put("longitude", locationLng)
                            })
                            put("radius", radiusMeters.toDouble())
                        })
                    })
                }
            }
            when (val result = PlacesProxyClient.searchText(body, FIELD_MASK)) {
                is NetworkResult.Success -> parsePlaces(result.data)
                is NetworkResult.HttpError -> {
                    logWarning("Places search failed [${result.code}]")
                    emptyList()
                }
                is NetworkResult.NetworkError -> {
                    logWarning("Places search network error", result.cause)
                    emptyList()
                }
                is NetworkResult.ParseError -> {
                    logWarning("Places search parse error", result.cause)
                    emptyList()
                }
                NetworkResult.Timeout -> {
                    logWarning("Places search timed out")
                    emptyList()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Places search failed", e)
            emptyList()
        }
    }

    private fun parsePlaces(response: JSONObject): List<PlacesMissionSearchResult> {
        val places = response.optJSONArray("places") ?: return emptyList()
        return (0 until places.length()).mapNotNull { index ->
            val place = places.optJSONObject(index) ?: return@mapNotNull null
            val location = place.optJSONObject("location") ?: return@mapNotNull null
            val name = place.optJSONObject("displayName")?.optString("text").orEmpty()
            if (name.isBlank()) return@mapNotNull null
            PlacesMissionSearchResult(
                placesName = name,
                placesId = place.optString("id").takeIf { it.isNotBlank() },
                latitude = location.optDouble("latitude"),
                longitude = location.optDouble("longitude"),
                locality = extractLocality(place),
                formattedAddress = place.optString("formattedAddress").takeIf { it.isNotBlank() },
                rating = place.optDoubleOrNull("rating"),
                userRatingsTotal = place.optIntOrNull("userRatingCount"),
                types = buildTypeSet(place),
                businessStatus = place.optString("businessStatus").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun extractLocality(place: JSONObject): String? {
        val components = place.optJSONArray("addressComponents") ?: return null
        for (index in 0 until components.length()) {
            val component = components.optJSONObject(index) ?: continue
            val types = component.optJSONArray("types") ?: continue
            val hasLocalityType = (0 until types.length()).any { typeIndex ->
                types.optString(typeIndex) in setOf("locality", "administrative_area_level_2", "administrative_area_level_1")
            }
            if (hasLocalityType) {
                return component.optString("longText")
                    .takeIf { it.isNotBlank() }
                    ?: component.optString("shortText").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun buildTypeSet(place: JSONObject): Set<String> {
        val types = mutableSetOf<String>()
        place.optString("primaryType").takeIf { it.isNotBlank() }?.let(types::add)
        val array = place.optJSONArray("types")
        if (array != null) {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(types::add)
            }
        }
        return types
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun logWarning(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val safe = LogRedactor.redact(message)
            val suffix = throwable?.let { " [${it.javaClass.simpleName}: ${LogRedactor.redact(it.message)}]" }.orEmpty()
            runCatching { AppLogger.w(TAG, safe + suffix) }
        }
    }

    companion object {
        private const val TAG = "GooglePlacesMissionPlaceSearchService"
        private const val FIELD_MASK =
            "places.id,places.location,places.displayName,places.formattedAddress,places.rating,places.userRatingCount,places.types,places.primaryType,places.businessStatus,places.addressComponents"
    }
}
