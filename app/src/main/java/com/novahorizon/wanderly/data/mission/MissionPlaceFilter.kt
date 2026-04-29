package com.novahorizon.wanderly.data.mission

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import javax.inject.Inject

class MissionPlaceFilter @Inject constructor() {

    fun filter(
        candidates: List<MissionPlaceCandidate>,
        hardRadiusMeters: Double,
        expectedCity: String
    ): Pair<List<MissionPlaceCandidate>, List<MissionPlaceCandidate>> {
        val accepted = mutableListOf<MissionPlaceCandidate>()
        val rejected = mutableListOf<MissionPlaceCandidate>()

        candidates.forEach { candidate ->
            val reason = getRejectReason(candidate, hardRadiusMeters)
            if (reason == null) {
                accepted += candidate
            } else {
                val rejectedCandidate = candidate.copy(rejectionReason = reason)
                rejected += rejectedCandidate
                logDebug(
                    "Rejected candidate name=\"${candidate.name}\" reason=$reason distance=${candidate.distanceMeters.toInt()}m"
                )
            }
        }

        logDebug("Accepted=${accepted.size} Rejected=${rejected.size}")
        logTopRejectionReasons(rejected)
        return accepted to rejected
    }

    private fun getRejectReason(candidate: MissionPlaceCandidate, hardRadiusMeters: Double): String? {
        if (candidate.name.isBlank()) return "missing_name"
        if (candidate.lat == 0.0 && candidate.lng == 0.0) return "missing_coordinates"
        if (candidate.distanceMeters > hardRadiusMeters) return "outside_hard_radius"

        val typesLower = candidate.types.map { it.lowercase() }
        val blockedType = BLOCKED_TYPES.firstOrNull { it in typesLower }
        if (blockedType != null) return "unsafe_type_$blockedType"

        val nameLower = candidate.name.lowercase()
        val blockedKeyword = BLOCKED_KEYWORDS.firstOrNull { it in nameLower }
        if (blockedKeyword != null) return "blocked_keyword_$blockedKeyword"

        return null
    }

    private fun logTopRejectionReasons(rejected: List<MissionPlaceCandidate>) {
        rejected.groupBy { it.rejectionReason }
            .entries
            .sortedByDescending { it.value.size }
            .take(5)
            .forEach { (reason, list) ->
                logDebug("Top rejection reason=$reason count=${list.size}")
            }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            runCatching { AppLogger.d(TAG, LogRedactor.redact(message)) }
        }
    }

    companion object {
        private const val TAG = "MissionPlaceFilter"

        val BLOCKED_TYPES = setOf(
            "school", "primary_school", "secondary_school", "university",
            "hospital", "doctor", "pharmacy", "police", "courthouse",
            "local_government_office", "lodging", "bar", "night_club",
            "casino", "church", "cemetery", "funeral_home"
        )

        val BLOCKED_KEYWORDS = setOf(
            "school", "scoala", "liceu", "colegiu", "universitate",
            "spital", "clinica", "politie", "tribunal",
            "judecatorie", "hotel", "pensiune", "bar", "club",
            "casino", "cazino", "cimitir", "funerar", "apartament",
            "residence", "residential"
        )
    }
}
