package com.novahorizon.wanderly.api

import com.novahorizon.wanderly.observability.AppLogger

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.observability.LogRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer

object PlacesGeocoder {
    private const val TAG = "PlacesGeocoder"
    private val exclusionTypes = setOf(
        "local_government_office", "government_office", "social_service_organization",
        "city_hall", "courthouse", "embassy", "fire_station", "police", "post_office",
        "school", "university", "dentist", "doctor", "hospital", "pharmacy",
        "bank", "atm", "lodging", "real_estate_agency", "car_repair", "car_wash",
        "lawyer", "funeral_home", "accounting", "veterinary_care"
    )
    private val weakTokens = setOf("the", "and", "of", "in", "la", "le", "de", "co")

    data class VerifiedPlace(
        val lat: Double,
        val lng: Double,
        val name: String,
        val formattedAddress: String? = null,
        val rating: Double = 0.0,
        val reviewCount: Int = 0,
        val description: String? = null
    )

    private fun normalize(value: String): String {
        val temp = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(temp, "")
    }

    suspend fun resolveCoordinates(
        placeName: String,
        targetCity: String,
        userLat: Double,
        userLng: Double,
        radiusKm: Double = 10.0,
        categoryHint: String? = null,
        strictNameMatch: Boolean = false
    ): VerifiedPlace? = withContext(Dispatchers.IO) {
        try {
            val normalizedCity = targetCity.trim()
            val textQuery = if (normalizedCity.isBlank()) placeName else "$placeName, $normalizedCity"

            val jsonBody = JSONObject().apply {
                put("textQuery", textQuery)
            }

            val responseJson = when (val result = PlacesProxyClient.searchText(
                body = jsonBody,
                fieldMask = "places.location,places.displayName,places.formattedAddress,places.rating,places.userRatingCount,places.editorialSummary,places.types,places.primaryType,places.businessStatus,places.currentOpeningHours"
            )) {
                is NetworkResult.Success -> result.data
                is NetworkResult.HttpError -> {
                    logError("Google Places proxy failed [${result.code}]")
                    return@withContext null
                }
                is NetworkResult.NetworkError -> {
                    logError("Google Places network error", result.cause)
                    return@withContext null
                }
                is NetworkResult.ParseError -> {
                    logError("Google Places parse error", result.cause)
                    return@withContext null
                }
                NetworkResult.Timeout -> {
                    logError("Google Places request timed out")
                    return@withContext null
                }
            }

            logDebug("--- Starting search: '$placeName' in '$targetCity' ---")
            if (!responseJson.has("places")) {
                logDebug("Google Places returned no candidates for '$placeName'")
                return@withContext null
            }

            val placesArray = responseJson.getJSONArray("places")
            if (placesArray.length() == 0) {
                logDebug("Places list is empty for '$textQuery'")
                return@withContext null
            }

            val expectedTypes = expectedTypesForCategory(categoryHint)
            var bestCandidate: VerifiedPlace? = null
            var bestScore = Double.NEGATIVE_INFINITY

            for (index in 0 until placesArray.length()) {
                val place = placesArray.getJSONObject(index)
                val verifiedName = place.getJSONObject("displayName").getString("text")
                val verifiedAddress = place.optString("formattedAddress", "")
                val businessStatus = place.optString("businessStatus", "OPERATIONAL")
                val placeTypes = buildTypeSet(place)

                if (businessStatus == "CLOSED_PERMANENTLY" || businessStatus == "CLOSED_TEMPORARILY") {
                    logDebug("Rejected '$verifiedName' because status is $businessStatus")
                    continue
                }

                val excludedType = placeTypes.firstOrNull(exclusionTypes::contains)
                if (excludedType != null) {
                    logDebug("Rejected '$verifiedName' because it is a service/office ($excludedType)")
                    continue
                }

                if (!isPlaceNameCompatible(placeName, verifiedName, verifiedAddress, strictNameMatch)) {
                    logDebug("Rejected '$verifiedName' because it does not closely match '$placeName'")
                    continue
                }

                val strongNameMatch = scoreNameMatch(placeName, verifiedName, verifiedAddress) >= 0.95
                if (expectedTypes.isNotEmpty() && placeTypes.none(expectedTypes::contains) && !strongNameMatch) {
                    logDebug(
                        "Rejected '$verifiedName' because its types do not fit category '$categoryHint'"
                    )
                    continue
                }

                val location = place.getJSONObject("location")
                val lat = location.getDouble("latitude")
                val lng = location.getDouble("longitude")
                if (!isWithinRadius(userLat, userLng, lat, lng, radiusKm)) {
                    logDebug("Rejected '$verifiedName' because it is outside the ${radiusKm}km radius")
                    continue
                }

                val score = scoreNameMatch(placeName, verifiedName, verifiedAddress) +
                    if (expectedTypes.isNotEmpty() && placeTypes.any(expectedTypes::contains)) 0.2 else 0.0 +
                    (place.optDouble("rating", 0.0) / 50.0) +
                    (place.optInt("userRatingCount", 0).coerceAtMost(500) / 5000.0)

                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = VerifiedPlace(
                        lat = lat,
                        lng = lng,
                        name = verifiedName,
                        formattedAddress = verifiedAddress,
                        rating = place.optDouble("rating", 0.0),
                        reviewCount = place.optInt("userRatingCount", 0),
                        description = place.optJSONObject("editorialSummary")?.optString("text")
                    )
                }
            }

            if (bestCandidate == null) {
                logDebug("No valid Places candidate matched '$placeName'")
            } else {
                logDebug("Validated place '${bestCandidate.name}' for '$placeName'")
            }

            bestCandidate
        } catch (e: Exception) {
            logError("Failed to resolve place", e)
            null
        }
    }

    internal fun isPlaceNameCompatible(
        requestedName: String,
        candidateName: String,
        candidateAddress: String = "",
        strict: Boolean
    ): Boolean {
        val score = scoreNameMatch(requestedName, candidateName, candidateAddress)
        return if (strict) score >= 0.6 else score >= 0.4
    }

    internal fun scoreNameMatch(
        requestedName: String,
        candidateName: String,
        candidateAddress: String = ""
    ): Double {
        val requested = normalize(requestedName)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val candidate = normalize(candidateName)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val address = normalize(candidateAddress)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (requested.isBlank() || candidate.isBlank()) return 0.0
        if (requested == candidate) return 1.0
        if (candidate.contains(requested) || requested.contains(candidate)) return 0.95

        val requestedTokens = meaningfulTokens(requested)
        val candidateTokens = meaningfulTokens("$candidate $address")
        if (requestedTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0

        val shared = requestedTokens.count { requestedToken ->
            candidateTokens.any { candidateToken -> tokensLookRelated(requestedToken, candidateToken) }
        }
        if (shared == 0) return 0.0

        return shared.toDouble() / requestedTokens.size.toDouble()
    }

    private fun buildTypeSet(place: JSONObject): Set<String> {
        val result = mutableSetOf<String>()
        val primaryType = place.optString("primaryType", "")
        if (primaryType.isNotBlank()) {
            result += primaryType
        }
        val typesArray = place.optJSONArray("types")
        if (typesArray != null) {
            for (index in 0 until typesArray.length()) {
                result += typesArray.getString(index)
            }
        }
        return result
    }

    private fun meaningfulTokens(value: String): List<String> {
        return value
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 2 && it !in weakTokens }
    }

    private fun tokensLookRelated(left: String, right: String): Boolean {
        if (left == right) return true
        if (left.length >= 4 && right.contains(left)) return true
        if (right.length >= 4 && left.contains(right)) return true
        val minLength = minOf(left.length, right.length)
        return minLength >= 4 && left.take(minLength - 1) == right.take(minLength - 1)
    }

    private fun expectedTypesForCategory(categoryHint: String?): Set<String> {
        return when (normalize(categoryHint.orEmpty())) {
            "food" -> setOf("restaurant", "cafe", "coffee_shop", "bakery", "meal_takeaway", "bar")
            "drinks" -> setOf("bar", "pub", "cafe", "coffee_shop", "night_club", "restaurant")
            "viewpoint" -> setOf("tourist_attraction", "park", "historical_landmark", "observation_deck", "point_of_interest")
            "culture" -> setOf("art_gallery", "museum", "cultural_landmark", "tourist_attraction", "historical_landmark", "library")
            else -> emptySet()
        }
    }

    private fun isWithinRadius(
        userLat: Double,
        userLng: Double,
        gemLat: Double,
        gemLng: Double,
        maxDistanceKm: Double
    ): Boolean {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(gemLat - userLat)
        val dLng = Math.toRadians(gemLng - userLng)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(userLat)) *
            Math.cos(Math.toRadians(gemLat)) *
            Math.sin(dLng / 2).let { it * it }
        val distance = earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return distance <= maxDistanceKm
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, LogRedactor.redact(message))
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val safeMessage = LogRedactor.redact(message)
            if (throwable != null) {
                AppLogger.e(TAG, "$safeMessage [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]")
            } else {
                AppLogger.e(TAG, safeMessage)
            }
        } else {
            AppLogger.e(TAG, message)
        }
    }
}
