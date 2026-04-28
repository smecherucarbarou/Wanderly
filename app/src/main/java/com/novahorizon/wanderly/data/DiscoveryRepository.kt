package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.util.GeoMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiscoveryRepository(
    private val overpassDataSource: OverpassDataSource = OverpassDataSource(),
    private val googlePlacesDataSource: GooglePlacesDataSource = GooglePlacesDataSource()
) {
    suspend fun fetchNearbyPlaces(lat: Double, lng: Double, radius: Int): List<String> = withContext(Dispatchers.IO) {
        overpassDataSource.fetchNearbyPlaceNames(lat, lng, radius)
    }

    suspend fun fetchHiddenGems(lat: Double, lng: Double, radius: Int): List<String> = withContext(Dispatchers.IO) {
        fetchHiddenGemCandidates(lat, lng, radius).map { it.name }
    }

    suspend fun fetchHiddenGemCandidates(lat: Double, lng: Double, radius: Int, city: String? = null): List<DiscoveredPlace> = withContext(Dispatchers.IO) {
        if (city.isNullOrBlank()) {
            val overpassOnly = overpassDataSource.fetchHiddenGemCandidates(lat, lng, radius)
                .filter(::isCandidateUserFriendly)
            return@withContext rankCandidates(overpassOnly, lat, lng).take(12)
        }

        val googleCandidates = googlePlacesDataSource.fetchHiddenGemCandidates(lat, lng, radius, city)
            .filter(::isCandidateUserFriendly)
        if (googleCandidates.size >= 6) {
            return@withContext rankCandidates(googleCandidates, lat, lng).take(12)
        }

        val overpassCandidates = overpassDataSource.fetchHiddenGemCandidates(lat, lng, radius)
            .filter(::isCandidateUserFriendly)
        val merged = googleCandidates + overpassCandidates.filter { overpass ->
            googleCandidates.none { google -> google.name.equals(overpass.name, ignoreCase = true) }
        }

        rankCandidates(merged, lat, lng)
            .distinctBy { it.name.lowercase() }
            .take(16)
    }

    private fun isCandidateUserFriendly(place: DiscoveredPlace): Boolean {
        val normalized = place.name.lowercase()
        val blockedTokens = listOf(
            "s.r.l", "srl", "impex", "pfa", "ii ", "\u00eei ", "societ", "depozit", "service", "atelier"
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
                compareByDescending<DiscoveredPlace> { CategoryMapper.priority(it.category) }
                    .thenByDescending { if (it.source == "google") 1 else 0 }
                    .thenByDescending { (it.reviewCount ?: 0) >= 50 }
                    .thenByDescending { (it.reviewCount ?: 0) >= 20 }
                    .thenByDescending { it.rating ?: 0.0 }
                    .thenBy { GeoMath.distanceKm(userLat, userLng, it.lat, it.lng) }
                    .thenBy { it.name.lowercase() }
            )
    }
}
