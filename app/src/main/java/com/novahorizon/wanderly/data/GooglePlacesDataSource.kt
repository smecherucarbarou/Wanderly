package com.novahorizon.wanderly.data

import android.util.Log
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.NetworkResult
import com.novahorizon.wanderly.api.PlacesProxyClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GooglePlacesDataSource {
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
    ): List<DiscoveredPlace> = withContext(Dispatchers.IO) {
        val queries = listOf(
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
        val candidates = coroutineScope {
            val semaphore = Semaphore(3)
            queries.map { query ->
                async {
                    semaphore.withPermit {
                        fetchCandidatesForQuery(query, userLat, userLng, radiusMeters)
                    }
                }
            }.awaitAll().flatten()
        }

        candidates
            .distinctBy { it.name.lowercase() }
            .sortedBy { distanceKm(userLat, userLng, it.lat, it.lng) }
    }

    private suspend fun fetchCandidatesForQuery(
        query: String,
        userLat: Double,
        userLng: Double,
        radiusMeters: Int
    ): List<DiscoveredPlace> {
        return try {
            val body = JSONObject().apply {
                put("textQuery", query)
            }
            val responseJson = when (val result = PlacesProxyClient.searchText(
                body = body,
                fieldMask = "places.displayName,places.location,places.formattedAddress,places.types,places.primaryType,places.businessStatus,places.rating,places.userRatingCount"
            )) {
                is NetworkResult.Success -> result.data
                is NetworkResult.HttpError -> {
                    logError("Google Places fallback HTTP error [${result.code}]")
                    return emptyList()
                }
                is NetworkResult.NetworkError -> {
                    logError("Google Places fallback network error [${result.cause.javaClass.simpleName}]")
                    return emptyList()
                }
                is NetworkResult.ParseError -> {
                    logError("Google Places fallback parse error [${result.cause.javaClass.simpleName}]")
                    return emptyList()
                }
                NetworkResult.Timeout -> {
                    logError("Google Places fallback timeout")
                    return emptyList()
                }
            }

            val places = responseJson.optJSONArray("places") ?: return emptyList()
            buildList {
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
                    if (distanceKm(userLat, userLng, lat, lng) > radiusMeters / 1000.0) continue

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
        } catch (e: Exception) {
            logError("Google Places fallback error [${e.javaClass.simpleName}]")
            emptyList()
        }
    }

    private fun logError(message: String) {
        if (BuildConfig.DEBUG) {
            Log.e("GooglePlacesDataSource", message)
        }
    }

    private fun distanceKm(userLat: Double, userLng: Double, placeLat: Double, placeLng: Double): Double {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(placeLat - userLat)
        val dLng = Math.toRadians(placeLng - userLng)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(userLat)) *
            Math.cos(Math.toRadians(placeLat)) *
            Math.sin(dLng / 2).let { it * it }
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
