package com.novahorizon.wanderly.data.mission

import javax.inject.Inject

class MissionPlaceScorer @Inject constructor() {

    fun score(candidate: MissionPlaceCandidate, expectedCity: String): Double {
        var score = 0.0

        score += when {
            candidate.distanceMeters <= 800 -> 40.0
            candidate.distanceMeters <= 1_500 -> 35.0
            candidate.distanceMeters <= 3_000 -> 28.0
            candidate.distanceMeters <= 5_000 -> 18.0
            candidate.distanceMeters <= 8_000 -> 8.0
            else -> 0.0
        }

        val types = candidate.types.map { it.lowercase() }
        if ("tourist_attraction" in types) score += 30.0
        if ("museum" in types) score += 25.0
        if ("park" in types) score += 22.0
        if ("art_gallery" in types) score += 18.0
        if ("point_of_interest" in types) score += 12.0
        if ("establishment" in types) score += 4.0

        candidate.rating?.let { rating ->
            score += when {
                rating >= 4.6 -> 15.0
                rating >= 4.2 -> 10.0
                rating >= 3.8 -> 5.0
                else -> 0.0
            }
        }

        candidate.userRatingsTotal?.let { count ->
            score += when {
                count >= 500 -> 10.0
                count >= 100 -> 7.0
                count >= 20 -> 4.0
                else -> 0.0
            }
        }

        val query = candidate.query.lowercase()
        if ("monument" in query) score += 5.0
        if ("park" in query) score += 4.0
        if ("museum" in query) score += 4.0
        if ("hidden" in query) score += 3.0

        if (candidate.city != null && !candidate.city.equals(expectedCity, ignoreCase = true)) {
            score -= 8.0
        }

        return score
    }
}
