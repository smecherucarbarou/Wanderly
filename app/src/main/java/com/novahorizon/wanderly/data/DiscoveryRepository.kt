package com.novahorizon.wanderly.data

import android.util.Log
import com.novahorizon.wanderly.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DiscoveryRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val overpassEndpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.nchc.org.tw/api/interpreter"
    )
    private val excludedPlaceTypes = setOf(
        "lodging", "local_government_office", "government_office", "social_service_organization",
        "city_hall", "courthouse", "embassy", "fire_station", "police", "post_office",
        "school", "university", "dentist", "doctor", "hospital", "pharmacy",
        "bank", "atm", "real_estate_agency", "car_repair", "car_wash",
        "lawyer", "funeral_home", "accounting", "veterinary_care"
    )

    suspend fun fetchNearbyPlaces(lat: Double, lng: Double, radius: Int): List<String> = withContext(Dispatchers.IO) {
        val query = "[out:json][timeout:25];(node[\"name\"](around:$radius,$lat,$lng);way[\"name\"](around:$radius,$lat,$lng););out center 20;"
        fetchFromOverpass(query)
    }

    suspend fun fetchHiddenGems(lat: Double, lng: Double, radius: Int): List<String> = withContext(Dispatchers.IO) {
        fetchHiddenGemCandidates(lat, lng, radius).map { it.name }
    }

    suspend fun fetchHiddenGemCandidates(lat: Double, lng: Double, radius: Int, city: String? = null): List<DiscoveredPlace> = withContext(Dispatchers.IO) {
        val query = """
            [out:json][timeout:25];
            (
              node["amenity"~"cafe|pub|bar|restaurant|arts_centre"](around:$radius,$lat,$lng);
              way["amenity"~"cafe|pub|bar|restaurant|arts_centre"](around:$radius,$lat,$lng);
              node["historic"](around:$radius,$lat,$lng);
              way["historic"](around:$radius,$lat,$lng);
              node["tourism"~"viewpoint|artwork|attraction|museum|gallery"](around:$radius,$lat,$lng);
              way["tourism"~"viewpoint|artwork|attraction|museum|gallery"](around:$radius,$lat,$lng);
              node["leisure"~"park|garden"](around:$radius,$lat,$lng);
              way["leisure"~"park|garden"](around:$radius,$lat,$lng);
            );
            out center 40;
        """.trimIndent()
        if (city.isNullOrBlank()) {
            val overpassOnly = fetchCandidatesFromOverpass(query).filter(::isCandidateUserFriendly)
            return@withContext rankCandidates(overpassOnly, lat, lng).take(12)
        }

        val googleCandidates = fetchCandidatesFromGooglePlaces(lat, lng, radius, city)
            .filter(::isCandidateUserFriendly)
        if (googleCandidates.size >= 6) {
            return@withContext rankCandidates(googleCandidates, lat, lng).take(12)
        }

        val overpassCandidates = fetchCandidatesFromOverpass(query)
            .filter(::isCandidateUserFriendly)
        val merged = googleCandidates + overpassCandidates.filter { overpass ->
            googleCandidates.none { google -> google.name.equals(overpass.name, ignoreCase = true) }
        }

        rankCandidates(merged, lat, lng)
            .distinctBy { it.name.lowercase() }
            .take(16)
    }

    private suspend fun fetchFromOverpass(query: String): List<String> = withContext(Dispatchers.IO) {
        fetchCandidatesFromOverpass(query).map { it.name }
    }

    private suspend fun fetchCandidatesFromOverpass(query: String): List<DiscoveredPlace> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val body = "data=$encodedQuery".toRequestBody("application/x-www-form-urlencoded".toMediaType())

        for (endpoint in overpassEndpoints) {
            try {
                val request = Request.Builder().url(endpoint).post(body).build()
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string()
                    if (bodyString.isNullOrBlank()) return@use
                    if (!response.isSuccessful || bodyString.trim().startsWith("<?xml")) return@use

                    val elements = JSONObject(bodyString).optJSONArray("elements") ?: return@use
                    val places = mutableListOf<DiscoveredPlace>()
                    for (i in 0 until elements.length()) {
                        val tags = elements.getJSONObject(i).optJSONObject("tags") ?: continue
                        val name = tags.optString("name", "").trim()
                        if (name.isEmpty()) continue

                        val element = elements.getJSONObject(i)
                        val latValue = when {
                            element.has("lat") -> element.optDouble("lat")
                            element.has("center") -> element.getJSONObject("center").optDouble("lat")
                            else -> Double.NaN
                        }
                        val lngValue = when {
                            element.has("lon") -> element.optDouble("lon")
                            element.has("center") -> element.getJSONObject("center").optDouble("lon")
                            else -> Double.NaN
                        }
                        if (latValue.isNaN() || lngValue.isNaN()) continue

                        places.add(
                            DiscoveredPlace(
                                name = name,
                                lat = latValue,
                                lng = lngValue,
                                category = mapCategory(tags),
                                areaLabel = buildAreaLabel(tags),
                                source = "overpass"
                            )
                        )
                    }
                    if (places.isNotEmpty()) {
                        return@withContext places
                            .distinctBy { it.name.lowercase() }
                            .sortedBy { it.name.lowercase() }
                    }
                }
            } catch (e: Exception) {
                Log.e("DiscoveryRepository", "Overpass error: ${e.message}")
            }
        }
        emptyList()
    }

    private suspend fun fetchCandidatesFromGooglePlaces(
        userLat: Double,
        userLng: Double,
        radiusMeters: Int,
        city: String
    ): List<DiscoveredPlace> = withContext(Dispatchers.IO) {
        if (BuildConfig.MAPS_API_KEY.isBlank()) return@withContext emptyList()

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
        val candidates = mutableListOf<DiscoveredPlace>()

        for (query in queries) {
            try {
                val body = JSONObject().apply {
                    put("textQuery", query)
                }
                val request = Request.Builder()
                    .url("https://places.googleapis.com/v1/places:searchText")
                    .addHeader("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
                    .addHeader(
                        "X-Goog-FieldMask",
                        "places.displayName,places.location,places.formattedAddress,places.types,places.primaryType,places.businessStatus,places.rating,places.userRatingCount"
                    )
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: return@use
                    if (!response.isSuccessful) return@use

                    val places = JSONObject(responseBody).optJSONArray("places") ?: return@use
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

                        candidates += DiscoveredPlace(
                            name = name,
                            lat = lat,
                            lng = lng,
                            category = mapCategoryFromTypes(typeSet),
                            areaLabel = place.optString("formattedAddress", "").substringBefore(",").trim().ifBlank { null },
                            source = "google",
                            rating = place.optDouble("rating").takeIf { !it.isNaN() && it > 0.0 },
                            reviewCount = place.optInt("userRatingCount").takeIf { it > 0 }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("DiscoveryRepository", "Google Places fallback error: ${e.message}")
            }
        }

        candidates
            .distinctBy { it.name.lowercase() }
            .sortedBy { distanceKm(userLat, userLng, it.lat, it.lng) }
    }

    private fun mapCategory(tags: JSONObject): String {
        val amenity = tags.optString("amenity")
        val tourism = tags.optString("tourism")
        val historic = tags.optString("historic")
        val leisure = tags.optString("leisure")

        return when {
            amenity in setOf("cafe", "restaurant") -> "Food"
            amenity in setOf("pub", "bar") -> "Drinks"
            tourism in setOf("museum", "gallery", "artwork") || historic.isNotBlank() -> "Culture"
            tourism in setOf("viewpoint", "attraction") || leisure in setOf("park", "garden") -> "Viewpoint"
            else -> "Culture"
        }
    }

    private fun mapCategoryFromTypes(types: Set<String>): String {
        return when {
            types.any { it in setOf("cafe", "restaurant", "bakery", "coffee_shop", "meal_takeaway") } -> "Food"
            types.any { it in setOf("bar", "pub", "night_club") } -> "Drinks"
            types.any { it in setOf("museum", "art_gallery", "cultural_landmark", "historical_landmark", "library", "artwork") } -> "Culture"
            types.any { it in setOf("tourist_attraction", "park", "garden", "point_of_interest", "observation_deck") } -> "Viewpoint"
            else -> "Culture"
        }
    }

    private fun buildAreaLabel(tags: JSONObject): String? {
        val street = tags.optString("addr:street").trim()
        val district = tags.optString("addr:suburb").trim()
        val neighbourhood = tags.optString("addr:neighbourhood").trim()
        return listOf(neighbourhood, district, street).firstOrNull { it.isNotBlank() }
    }

    private fun isCandidateUserFriendly(place: DiscoveredPlace): Boolean {
        val normalized = place.name.lowercase()
        val blockedTokens = listOf(
            "s.r.l", "srl", "impex", "pfa", "ii ", "îi ", "societ", "depozit", "service", "atelier"
        )
        if (blockedTokens.any { normalized.contains(it) }) return false
        if (place.category in setOf("Food", "Drinks") && normalized.split(" ").size > 6) return false
        if (normalized.startsWith("boiangeria ")) return false
        if (normalized.startsWith("casa memoriala")) return false
        return true
    }

    private fun rankCandidates(candidates: List<DiscoveredPlace>, userLat: Double, userLng: Double): List<DiscoveredPlace> {
        return candidates
            .sortedWith(
                compareByDescending<DiscoveredPlace> { categoryPriority(it.category) }
                    .thenByDescending { if (it.source == "google") 1 else 0 }
                    .thenByDescending { (it.reviewCount ?: 0) >= 50 }
                    .thenByDescending { (it.reviewCount ?: 0) >= 20 }
                    .thenByDescending { it.rating ?: 0.0 }
                    .thenBy { distanceKm(userLat, userLng, it.lat, it.lng) }
                    .thenBy { it.name.lowercase() }
            )
    }

    private fun categoryPriority(category: String): Int {
        return when (category) {
            "Food" -> 4
            "Drinks" -> 3
            "Culture" -> 2
            "Viewpoint" -> 2
            else -> 0
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
