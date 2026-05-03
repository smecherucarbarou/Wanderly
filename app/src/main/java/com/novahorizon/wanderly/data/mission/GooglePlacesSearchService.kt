package com.novahorizon.wanderly.data.mission

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.NetworkResult
import com.novahorizon.wanderly.api.PlacesProxyClient
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

sealed class PlacesSearchResult {
    data class Success(val places: List<PlacesMissionSearchResult>) : PlacesSearchResult()
    data class Error(
        val reason: Reason,
        val statusCode: Int? = null,
        val message: String? = null
    ) : PlacesSearchResult()

    enum class Reason {
        BadRequest,
        Unauthorized,
        Forbidden,
        Server,
        Network,
        Timeout,
        Parse,
        Unknown
    }
}

class GooglePlacesSearchService(
    private val searchText: suspend (JSONObject, String) -> NetworkResult<JSONObject> = PlacesProxyClient::searchText,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) }
) : MissionPlaceSearchService {

    override suspend fun searchText(query: String): List<PlacesMissionSearchResult> {
        return when (val result = searchTextResult(query)) {
            is PlacesSearchResult.Success -> result.places
            is PlacesSearchResult.Error -> emptyList()
        }
    }

    suspend fun searchTextResult(
        query: String,
        locationLat: Double? = null,
        locationLng: Double? = null,
        radiusMeters: Int? = null
    ): PlacesSearchResult {
        val body = buildRequestBody(query, locationLat, locationLng, radiusMeters)
        var attempt = 1

        while (attempt <= MAX_ATTEMPTS) {
            try {
                when (val result = searchText(body, FIELD_MASK)) {
                    is NetworkResult.Success -> return PlacesSearchResult.Success(parsePlaces(result.data))
                    is NetworkResult.HttpError -> {
                        val error = mapHttpError(result)
                        if (result.code in 500..599 && attempt < MAX_ATTEMPTS) {
                            logWarning("Places search failed [${result.code}], retrying attempt ${attempt + 1}")
                            retryDelay(INITIAL_RETRY_DELAY_MS * (1L shl (attempt - 1)))
                            attempt++
                            continue
                        }
                        logWarning("Places search failed [${result.code}]")
                        return error
                    }
                    is NetworkResult.NetworkError -> {
                        logWarning("Places search network error", result.cause)
                        return PlacesSearchResult.Error(
                            reason = PlacesSearchResult.Reason.Network,
                            message = result.cause.message
                        )
                    }
                    is NetworkResult.ParseError -> {
                        logWarning("Places search parse error", result.cause)
                        return PlacesSearchResult.Error(
                            reason = PlacesSearchResult.Reason.Parse,
                            message = result.cause.message
                        )
                    }
                    NetworkResult.Timeout -> {
                        logWarning("Places search timed out")
                        return PlacesSearchResult.Error(reason = PlacesSearchResult.Reason.Timeout)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Places search failed", e)
                return PlacesSearchResult.Error(
                    reason = PlacesSearchResult.Reason.Unknown,
                    message = e.message
                )
            }
        }

        return PlacesSearchResult.Error(
            reason = PlacesSearchResult.Reason.Server,
            message = "Max Places retry attempts exceeded"
        )
    }

    private fun buildRequestBody(
        query: String,
        locationLat: Double?,
        locationLng: Double?,
        radiusMeters: Int?
    ): JSONObject {
        return JSONObject().apply {
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
    }

    private fun mapHttpError(result: NetworkResult.HttpError): PlacesSearchResult.Error {
        val reason = when (result.code) {
            400 -> PlacesSearchResult.Reason.BadRequest
            401 -> PlacesSearchResult.Reason.Unauthorized
            403 -> PlacesSearchResult.Reason.Forbidden
            in 500..599 -> PlacesSearchResult.Reason.Server
            else -> PlacesSearchResult.Reason.Unknown
        }
        return PlacesSearchResult.Error(
            reason = reason,
            statusCode = result.code,
            message = result.message
        )
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

    private companion object {
        private const val TAG = "GooglePlacesSearchService"
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 500L
        private const val FIELD_MASK =
            "places.id,places.location,places.displayName,places.formattedAddress,places.rating,places.userRatingCount,places.types,places.primaryType,places.businessStatus,places.addressComponents"
    }
}
