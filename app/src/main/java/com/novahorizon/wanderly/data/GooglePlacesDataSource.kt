package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.observability.AppLogger

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.NetworkResult
import com.novahorizon.wanderly.api.PlacesProxyClient
import com.novahorizon.wanderly.util.GeoMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GooglePlacesDataSource(
    private val searchText: suspend (JSONObject, String) -> NetworkResult<JSONObject> = PlacesProxyClient::searchText,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val queryBuilder: (String) -> List<String> = Companion::defaultQueries
) {
    private val excludedPlaceTypes = setOf(
        "lodging", "local_government_office", "government_office", "social_service_organization",
        "city_hall", "courthouse", "embassy", "fire_station", "police", "post_office",
        "school", "university", "dentist", "doctor", "hospital", "pharmacy",
        "bank", "atm", "real_estate_agency", "car_repair", "car_wash",
        "lawyer", "funeral_home", "accounting", "veterinary_care"
    )

    suspend fun fetchHiddenGemCandidates(
        userLat: Double,
        userLng: Double,
        radiusMeters: Int,
        city: String
    ): List<DiscoveredPlace> =
        when (val result = fetchHiddenGemCandidatesResult(userLat, userLng, radiusMeters, city)) {
            is HiddenGemCandidateResult.Success -> result.candidates
            is HiddenGemCandidateResult.Error -> {
                logError("Google Places fallback failed [${result.reason}, status=${result.statusCode}]")
                emptyList()
            }
        }

    suspend fun fetchHiddenGemCandidatesResult(
        userLat: Double,
        userLng: Double,
        radiusMeters: Int,
        city: String
    ): HiddenGemCandidateResult = withContext(Dispatchers.IO) {
        val queries = queryBuilder(city)
        val queryResults = coroutineScope {
            val semaphore = Semaphore(3)
            queries.map { query ->
                async {
                    semaphore.withPermit {
                        fetchCandidatesForQuery(query, userLat, userLng, radiusMeters)
                    }
                }
            }.awaitAll()
        }

        val errors = queryResults.filterIsInstance<PlacesQueryResult.Failure>().map { it.error }
        val blockingError = errors.firstOrNull {
            it.reason == HiddenGemCandidateResult.Reason.BadRequest ||
                it.reason == HiddenGemCandidateResult.Reason.Unauthorized ||
                it.reason == HiddenGemCandidateResult.Reason.Forbidden
        }
        if (blockingError != null) {
            return@withContext blockingError
        }

        val candidates = queryResults
            .filterIsInstance<PlacesQueryResult.Success>()
            .flatMap { it.candidates }
            .distinctBy { it.name.lowercase() }
            .sortedBy { GeoMath.distanceKm(userLat, userLng, it.lat, it.lng) }

        if (candidates.isNotEmpty()) {
            HiddenGemCandidateResult.Success(candidates)
        } else {
            errors.firstOrNull() ?: HiddenGemCandidateResult.Success(emptyList())
        }
    }

    private suspend fun fetchCandidatesForQuery(
        query: String,
        userLat: Double,
        userLng: Double,
        radiusMeters: Int
    ): PlacesQueryResult {
        return try {
            val responseJson = when (val result = searchTextWithRetry(query)) {
                is SearchTextResult.Success -> result.data
                is SearchTextResult.Failure -> return PlacesQueryResult.Failure(result.error)
            }

            val places = responseJson.optJSONArray("places") ?: return PlacesQueryResult.Success(emptyList())
            PlacesQueryResult.Success(parsePlaces(places, userLat, userLng, radiusMeters))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Google Places fallback error [${e.javaClass.simpleName}]")
            PlacesQueryResult.Failure(
                HiddenGemCandidateResult.Error(
                    reason = HiddenGemCandidateResult.Reason.Unknown,
                    message = e.message
                )
            )
        }
    }

    private suspend fun searchTextWithRetry(query: String): SearchTextResult {
        val body = JSONObject().apply {
            put("textQuery", query)
        }

        var attempt = 1
        while (attempt <= MAX_ATTEMPTS) {
            when (val result = searchText(body, FIELD_MASK)) {
                is NetworkResult.Success -> return SearchTextResult.Success(result.data)
                is NetworkResult.HttpError -> {
                    val error = mapHttpError(result)
                    if (result.code in 500..599 && attempt < MAX_ATTEMPTS) {
                        logError("Google Places fallback HTTP error [${result.code}], retrying attempt ${attempt + 1}")
                        retryDelay(INITIAL_RETRY_DELAY_MS * (1L shl (attempt - 1)))
                        attempt++
                        continue
                    }
                    logError("Google Places fallback HTTP error [${result.code}]")
                    return SearchTextResult.Failure(error)
                }
                is NetworkResult.NetworkError -> {
                    logError("Google Places fallback network error [${result.cause.javaClass.simpleName}]")
                    return SearchTextResult.Failure(
                        HiddenGemCandidateResult.Error(
                            reason = HiddenGemCandidateResult.Reason.Network,
                            message = result.cause.message
                        )
                    )
                }
                is NetworkResult.ParseError -> {
                    logError("Google Places fallback parse error [${result.cause.javaClass.simpleName}]")
                    return SearchTextResult.Failure(
                        HiddenGemCandidateResult.Error(
                            reason = HiddenGemCandidateResult.Reason.Parse,
                            message = result.cause.message
                        )
                    )
                }
                NetworkResult.Timeout -> {
                    logError("Google Places fallback timeout")
                    return SearchTextResult.Failure(
                        HiddenGemCandidateResult.Error(reason = HiddenGemCandidateResult.Reason.Timeout)
                    )
                }
            }
        }

        return SearchTextResult.Failure(
            HiddenGemCandidateResult.Error(
                reason = HiddenGemCandidateResult.Reason.Server,
                message = "Max Google Places retry attempts exceeded"
            )
        )
    }

    private fun mapHttpError(result: NetworkResult.HttpError): HiddenGemCandidateResult.Error {
        val reason = when (result.code) {
            400 -> HiddenGemCandidateResult.Reason.BadRequest
            401 -> HiddenGemCandidateResult.Reason.Unauthorized
            403 -> HiddenGemCandidateResult.Reason.Forbidden
            in 500..599 -> HiddenGemCandidateResult.Reason.Server
            else -> HiddenGemCandidateResult.Reason.Unknown
        }
        return HiddenGemCandidateResult.Error(
            reason = reason,
            statusCode = result.code,
            message = result.message
        )
    }

    private fun parsePlaces(
        places: org.json.JSONArray,
        userLat: Double,
        userLng: Double,
        radiusMeters: Int
    ): List<DiscoveredPlace> {
        return buildList {
            for (index in 0 until places.length()) {
                val place = places.getJSONObject(index)
                val businessStatus = place.optString("businessStatus", "OPERATIONAL")
                if (businessStatus == "CLOSED_PERMANENTLY" || businessStatus == "CLOSED_TEMPORARILY") continue

                val typeSet = mutableSetOf<String>()
                val primaryType = place.optString("primaryType", "")
                if (primaryType.isNotBlank()) typeSet += primaryType
                val typesArray = place.optJSONArray("types")
                if (typesArray != null) {
                    for (typeIndex in 0 until typesArray.length()) {
                        typeSet += typesArray.getString(typeIndex)
                    }
                }
                if (typeSet.any(excludedPlaceTypes::contains)) continue

                val location = place.optJSONObject("location") ?: continue
                val lat = location.optDouble("latitude", Double.NaN)
                val lng = location.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue
                if (GeoMath.distanceKm(userLat, userLng, lat, lng) > radiusMeters / 1000.0) continue

                val name = place.optJSONObject("displayName")?.optString("text").orEmpty().trim()
                if (name.isBlank()) continue

                add(
                    DiscoveredPlace(
                        name = name,
                        lat = lat,
                        lng = lng,
                        category = CategoryMapper.fromGoogleTypes(typeSet),
                        areaLabel = place.optString("formattedAddress", "").substringBefore(",").trim().ifBlank { null },
                        source = "google",
                        rating = place.optDouble("rating").takeIf { !it.isNaN() && it > 0.0 },
                        reviewCount = place.optInt("userRatingCount").takeIf { it > 0 }
                    )
                )
            }
        }
    }

    private sealed class PlacesQueryResult {
        data class Success(val candidates: List<DiscoveredPlace>) : PlacesQueryResult()
        data class Failure(val error: HiddenGemCandidateResult.Error) : PlacesQueryResult()
    }

    private sealed class SearchTextResult {
        data class Success(val data: JSONObject) : SearchTextResult()
        data class Failure(val error: HiddenGemCandidateResult.Error) : SearchTextResult()
    }

    private fun logError(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.e("GooglePlacesDataSource", message)
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 500L
        private const val FIELD_MASK =
            "places.displayName,places.location,places.formattedAddress,places.types,places.primaryType,places.businessStatus,places.rating,places.userRatingCount"

        private fun defaultQueries(city: String): List<String> = listOf(
            "specialty coffee in $city",
            "cafe in $city",
            "bakery in $city",
            "ice cream in $city",
            "dessert in $city",
            "family restaurant in $city",
            "brunch in $city",
            "cocktail bar in $city",
            "lounge bar in $city",
            "wine bar in $city",
            "restaurant in $city",
            "art gallery in $city",
            "museum in $city",
            "tourist attraction in $city",
            "park in $city"
        )
    }
}
