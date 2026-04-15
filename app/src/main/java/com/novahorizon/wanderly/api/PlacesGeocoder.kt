package com.novahorizon.wanderly.api

import com.novahorizon.wanderly.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Normalizer

object PlacesGeocoder {

    private val client = OkHttpClient()

    /**
     * Resolves a place name to lat/lng and verified name using Google Places API (New).
     * Returns a data class with verified details.
     */
    data class VerifiedPlace(
        val lat: Double,
        val lng: Double,
        val name: String,
        val formattedAddress: String? = null,
        val rating: Double = 0.0,
        val reviewCount: Int = 0,
        val description: String? = null
    )

    private fun normalize(s: String): String {
        val temp = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(temp, "")
    }

    suspend fun resolveCoordinates(
        placeName: String,
        targetCity: String,
        userLat: Double,
        userLng: Double,
        radiusKm: Double = 10.0
    ): VerifiedPlace? = withContext(Dispatchers.IO) {
        try {
            val url = "https://places.googleapis.com/v1/places:searchText"
            val normalizedCity = targetCity.trim()
            val textQuery = if (normalizedCity.isBlank()) placeName else "$placeName, $normalizedCity"
            
            val jsonBody = org.json.JSONObject().apply {
                put("textQuery", textQuery)
            }

            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("X-Goog-Api-Key", com.novahorizon.wanderly.BuildConfig.MAPS_API_KEY)
                .addHeader("X-Goog-FieldMask", "places.location,places.displayName,places.formattedAddress,places.rating,places.userRatingCount,places.editorialSummary,places.types,places.primaryType,places.businessStatus,places.currentOpeningHours")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            // --- LOGURI ADAUGATE PENTRU DEBUGGING ---
            android.util.Log.d("PlacesGeocoder", "--- Start căutare: '$placeName' în '$targetCity' ---")
            android.util.Log.d("PlacesGeocoder", "Cod răspuns HTTP: ${response.code}")
            
            val json = org.json.JSONObject(responseBody)
            
            if (!json.has("places")) {
                android.util.Log.e("PlacesGeocoder", "Google Maps nu a gasit locatia sau a dat eroare pentru '$placeName'! Raspuns complet: $responseBody")
                return@withContext null
            }

            val placesArray = json.getJSONArray("places")
            if (placesArray.length() == 0) {
                android.util.Log.w("PlacesGeocoder", "Lista 'places' este goală pentru query: '$placeName, $targetCity'")
                return@withContext null
            }
            
            val firstPlace = placesArray.getJSONObject(0)
            val verifiedName = firstPlace.getJSONObject("displayName").getString("text")

            // --- BULLSHIT FILTER (Anti-Irrelevance & Status Check) ---
            val businessStatus = firstPlace.optString("businessStatus", "OPERATIONAL")
            if (businessStatus == "CLOSED_PERMANENTLY" || businessStatus == "CLOSED_TEMPORARILY") {
                android.util.Log.w("PlacesGeocoder", "REJECTED: '$verifiedName' a fost respinsă fiindcă businessStatus este: $businessStatus")
                return@withContext null
            }

            val typesArray = firstPlace.optJSONArray("types")
            val primaryType = firstPlace.optString("primaryType", "")
            val exclusionList = listOf(
                "local_government_office", "government_office", "social_service_organization", 
                "city_hall", "courthouse", "embassy", "fire_station", "police", "post_office", 
                "school", "university", "dentist", "doctor", "hospital", "pharmacy", 
                "bank", "atm", "lodging", "real_estate_agency", "car_repair", "car_wash", 
                "lawyer", "funeral_home", "accounting", "veterinary_care"
            )
            
            if (exclusionList.contains(primaryType)) {
                android.util.Log.w("PlacesGeocoder", "REJECTED: '$verifiedName' is a public office or service ($primaryType), not a gem.")
                return@withContext null
            }

            if (typesArray != null) {
                for (i in 0 until typesArray.length()) {
                    val type = typesArray.getString(i)
                    if (exclusionList.contains(type)) {
                        android.util.Log.w("PlacesGeocoder", "REJECTED: '$verifiedName' is a public office or service ($type), not a gem.")
                        return@withContext null
                    }
                }
            }

            val verifiedAddress = firstPlace.optString("formattedAddress", "")
            
            val loc = firstPlace.getJSONObject("location")
            val lat = loc.getDouble("latitude")
            val lng = loc.getDouble("longitude")

            android.util.Log.d("PlacesGeocoder", "Găsit: $verifiedName la coordonatele ($lat, $lng)")

            if (!isWithinRadius(userLat, userLng, lat, lng, radiusKm)) {
                android.util.Log.d("PlacesGeocoder", "Locatia $verifiedName a fost gasita, dar e la mai mult de $radiusKm km departare de tine ($userLat, $userLng). O ignor.")
                return@withContext null
            }

            val editorialSummary = firstPlace.optJSONObject("editorialSummary")?.optString("text")

            android.util.Log.d("PlacesGeocoder", "Locatie validata cu succes: $verifiedName")
            VerifiedPlace(
                lat = lat,
                lng = lng,
                name = verifiedName,
                formattedAddress = verifiedAddress,
                rating = firstPlace.optDouble("rating", 0.0),
                reviewCount = firstPlace.optInt("userRatingCount", 0),
                description = editorialSummary
            )
        } catch (e: Exception) {
            android.util.Log.e("PlacesGeocoder", "A crapat cand incercam sa parsam JSON-ul de la Google Maps pentru '$placeName'", e)
            null
        }
    }

    private fun isWithinRadius(
        userLat: Double, userLng: Double,
        gemLat: Double, gemLng: Double,
        maxDistanceKm: Double
    ): Boolean {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(gemLat - userLat)
        val dLng = Math.toRadians(gemLng - userLng)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(userLat)) *
                Math.cos(Math.toRadians(gemLat)) *
                Math.sin(dLng / 2).let { it * it }
        val distance = earthRadius * 2 * Math.atan2(
            Math.sqrt(a), Math.sqrt(1 - a)
        )
        return distance <= maxDistanceKm
    }
}
