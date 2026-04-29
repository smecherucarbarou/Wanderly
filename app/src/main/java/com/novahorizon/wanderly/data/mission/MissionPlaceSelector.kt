package com.novahorizon.wanderly.data.mission

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.PlacesGeocoder
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import kotlinx.coroutines.CancellationException
import java.util.Locale
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class MissionPlaceSelector @Inject constructor(
    private val geminiQueryProvider: GeminiMissionQueryProvider,
    private val deterministicQueryProvider: DeterministicMissionQueryProvider,
    private val candidateFetcher: GooglePlacesCandidateFetcher,
    private val filter: MissionPlaceFilter,
    private val scorer: MissionPlaceScorer
) : MissionPlaceSelecting {

    constructor(searchService: MissionPlaceSearchService) : this(
        geminiQueryProvider = GeminiMissionQueryProvider(NoopGeminiQueryTextClient),
        deterministicQueryProvider = DeterministicMissionQueryProvider(),
        candidateFetcher = GooglePlacesCandidateFetcher(searchService),
        filter = MissionPlaceFilter(),
        scorer = MissionPlaceScorer()
    )

    suspend fun selectMissionPlace(
        userLat: Double,
        userLng: Double,
        city: String
    ): MissionPlaceResult {
        val geminiQueries = tryGeminiQueries(city)
        val deterministicQueries = deterministicQueryProvider.getQueries(city)
        val allQueries = normalizeQueries(geminiQueries + deterministicQueries).take(MAX_TOTAL_QUERY_GROUPS)

        var result = fetchFilterScoreSelect(
            queries = allQueries,
            userLat = userLat,
            userLng = userLng,
            city = city,
            radius = GooglePlacesCandidateFetcher.HARD_RADIUS,
            source = CandidateSource.GOOGLE_PLACES
        )

        if (result == null) {
            logWarning("No candidates within hard radius, retrying with emergency radius=${GooglePlacesCandidateFetcher.EMERGENCY_RADIUS}")
            result = fetchFilterScoreSelect(
                queries = allQueries,
                userLat = userLat,
                userLng = userLng,
                city = city,
                radius = GooglePlacesCandidateFetcher.EMERGENCY_RADIUS,
                source = CandidateSource.GOOGLE_PLACES
            )
        }

        if (result == null) {
            logWarning("No Places candidates found, attempting local safe fallback")
            result = resolveLocalFallback(city, userLat, userLng)
        }

        return result ?: MissionPlaceResult.Error("Nu s-a gasit nicio locatie disponibila. Incearca din nou.")
    }

    override suspend fun selectBestMissionPlace(
        userLat: Double,
        userLng: Double,
        city: String,
        countryRegion: String?,
        missionType: String,
        candidates: List<MissionPlaceCandidate>
    ): MissionPlaceSelectionResult {
        logDebug("AI returned ${candidates.size} candidates")
        val aiQueries = candidates.map { it.query }.filter { it.isNotBlank() }
        val deterministicQueries = deterministicQueryProvider.getQueries(city)
        val allQueries = normalizeQueries(aiQueries + deterministicQueries).take(MAX_TOTAL_QUERY_GROUPS)

        var selected = fetchFilterScoreSelect(
            queries = allQueries,
            userLat = userLat,
            userLng = userLng,
            city = city,
            radius = radiusPolicyFor(missionType).hardMaxMeters.toInt(),
            source = CandidateSource.GOOGLE_PLACES
        )

        if (selected == null) {
            selected = fetchFilterScoreSelect(
                queries = allQueries,
                userLat = userLat,
                userLng = userLng,
                city = city,
                radius = GooglePlacesCandidateFetcher.EMERGENCY_RADIUS,
                source = CandidateSource.GOOGLE_PLACES
            )
        }

        val success = selected ?: return MissionPlaceSelectionResult.Fallback("No valid public place found")
        return MissionPlaceSelectionResult.Success(success.place.toValidatedMissionPlace(candidates.firstOrNull() ?: success.place))
    }

    private suspend fun tryGeminiQueries(city: String): List<String> {
        return try {
            logDebug("Gemini query generation started model=gemini-3-flash-preview")
            when (val result = geminiQueryProvider.generateQueries(city)) {
                is GeminiQueryResult.Success -> {
                    logDebug("Gemini queries generated count=${result.queries.size}")
                    result.queries
                }
                is GeminiQueryResult.Failure -> {
                    logWarning("Gemini query generation failed: ${result.error}; continuing deterministic Places fallback")
                    emptyList()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWarning("Gemini exception: ${e.message}; continuing deterministic Places fallback")
            emptyList()
        }
    }

    private suspend fun fetchFilterScoreSelect(
        queries: List<String>,
        userLat: Double,
        userLng: Double,
        city: String,
        radius: Int,
        source: CandidateSource
    ): MissionPlaceResult.Success? {
        val raw = candidateFetcher.fetchAllCandidates(queries, userLat, userLng, radius, source)
        logDebug("Raw candidates=${raw.size}")

        val deduped = deduplicateCandidates(raw)
        logDebug("Deduped candidates=${deduped.size}")

        val (accepted, rejected) = filter.filter(deduped, radius.toDouble(), city)
        logDebug("Accepted candidates=${accepted.size}")
        if (accepted.isEmpty()) return null

        val finalSource = if (source == CandidateSource.GOOGLE_PLACES && queries.any { it in deterministicQueryProvider.getQueries(city) }) {
            CandidateSource.DETERMINISTIC_PLACES_FALLBACK
        } else {
            source
        }
        val scored = accepted.map { candidate ->
            candidate.copy(
                score = scorer.score(candidate, city),
                source = finalSource,
                rejectionReason = null
            )
        }.sortedByDescending { it.score }

        scored.take(8).forEach { candidate ->
            logDebug(
                "Accepted candidate name=\"${candidate.name}\" score=${candidate.score} distance=${candidate.distanceMeters.toInt()}m source=${candidate.source}"
            )
        }

        val best = scored.first()
        logDebug(
            "Selected place=\"${best.name}\" distance=${best.distanceMeters.toInt()}m score=${best.score} placeId=${best.placeId} source=${best.source}"
        )
        return MissionPlaceResult.Success(
            best.copy(
                reason = best.reason ?: "Selected from ${raw.size} raw candidates; rejected=${rejected.size}",
                validationReason = "Selected from ${raw.size} raw Google Places candidates after filtering and scoring.",
                rejectionLogs = rejected,
                rejectionReason = null
            )
        )
    }

    private fun resolveLocalFallback(city: String, userLat: Double, userLng: Double): MissionPlaceResult.Success? {
        if (!city.equals("Targu Jiu", ignoreCase = true) && !city.equals("Targu-Jiu", ignoreCase = true)) {
            return null
        }
        val fallback = MissionPlaceCandidate(
            placeId = null,
            name = LOCAL_FALLBACK_TARGU_JIU.first(),
            lat = userLat,
            lng = userLng,
            address = city,
            city = city,
            types = listOf("park", "point_of_interest"),
            rating = null,
            userRatingsTotal = null,
            openNow = null,
            distanceMeters = 0.0,
            source = CandidateSource.LOCAL_SAFE_FALLBACK,
            query = "local safe fallback $city",
            reason = "No Google Places candidates survived filtering; local safe fallback used.",
            validationReason = "Local safe fallback used after Google Places returned no valid candidates.",
            score = 1.0
        )
        return MissionPlaceResult.Success(fallback)
    }

    internal fun scorePlaceCandidate(
        candidate: MissionPlaceCandidate,
        place: PlacesMissionSearchResult,
        userLat: Double,
        userLng: Double,
        city: String,
        missionType: String
    ): CandidateScore {
        val radius = radiusPolicyFor(missionType)
        val distanceMeters = distanceMeters(userLat, userLng, place.latitude, place.longitude)
        val merged = candidate.copy(
            placeId = place.placesId,
            name = place.placesName,
            lat = place.latitude,
            lng = place.longitude,
            address = place.formattedAddress,
            city = place.locality,
            types = place.types.toList(),
            rating = place.rating,
            userRatingsTotal = place.userRatingsTotal,
            distanceMeters = distanceMeters
        )

        if (distanceMeters > radius.hardMaxMeters) {
            return CandidateScore(null, "outside_hard_radius", 0.0, distanceMeters)
        }
        if (place.businessStatus in setOf("CLOSED_PERMANENTLY", "CLOSED_TEMPORARILY")) {
            return CandidateScore(null, "closed", 0.0, distanceMeters)
        }
        val (_, rejected) = filter.filter(listOf(merged), radius.hardMaxMeters, city)
        if (rejected.isNotEmpty()) {
            return CandidateScore(null, rejected.first().rejectionReason ?: "rejected", 0.0, distanceMeters)
        }

        val nameScore = listOfNotNull(candidate.name, candidate.localName, candidate.query)
            .maxOfOrNull { PlacesGeocoder.scoreNameMatch(it, place.placesName, place.formattedAddress.orEmpty()) }
            ?: 0.0
        if (nameScore < 0.20 && place.placesId.isNullOrBlank()) {
            return CandidateScore(null, "name_mismatch", 0.0, distanceMeters)
        }

        val finalScore = scorer.score(merged, city) / 120.0
        return if (finalScore < MIN_CONFIDENCE_SCORE) {
            CandidateScore(null, "low_confidence", finalScore, distanceMeters)
        } else {
            CandidateScore(
                validated = merged.copy(score = finalScore).toValidatedMissionPlace(candidate),
                reason = "accepted",
                score = finalScore,
                distanceMeters = distanceMeters
            )
        }
    }

    data class CandidateScore(
        val validated: ValidatedMissionPlace?,
        val reason: String,
        val score: Double,
        val distanceMeters: Double
    )

    private fun MissionPlaceCandidate.toValidatedMissionPlace(original: MissionPlaceCandidate): ValidatedMissionPlace {
        return ValidatedMissionPlace(
            originalCandidate = original,
            placesName = name,
            placesId = placeId,
            latitude = lat,
            longitude = lng,
            distanceMeters = distanceMeters,
            locality = city,
            formattedAddress = address,
            rating = rating,
            userRatingsTotal = userRatingsTotal,
            confidenceScore = score,
            source = source,
            validationReason = reason ?: "Selected from Google Places after deterministic filtering.",
            rejectionLogs = emptyList()
        )
    }

    private fun radiusPolicyFor(missionType: String): MissionRadiusPolicy {
        return when (missionType.lowercase(Locale.US).replace(" ", "_")) {
            "walking", "nearby" -> WALKING_RADIUS
            "hidden_gem", "hidden gem" -> HIDDEN_GEM_RADIUS
            "city_exploration", "city exploration" -> CITY_EXPLORATION_RADIUS
            else -> LANDMARK_RADIUS
        }
    }

    private fun normalizeQueries(queries: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        queries.forEach { query ->
            val normalized = query.trim().replace(Regex("\\s+"), " ")
            if (normalized.isNotBlank()) {
                seen += normalized
            }
        }
        return seen.distinctBy { MissionCandidateParser.normalize(it) }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            runCatching { AppLogger.d(TAG, LogRedactor.redact(message)) }
        }
    }

    private fun logWarning(message: String) {
        if (BuildConfig.DEBUG) {
            runCatching { AppLogger.w(TAG, LogRedactor.redact(message)) }
        }
    }

    companion object {
        private const val TAG = "MissionPlaceSelector"
        private const val MIN_CONFIDENCE_SCORE = 0.55
        private const val MAX_TOTAL_QUERY_GROUPS = 20
        val WALKING_RADIUS = MissionRadiusPolicy(preferredMeters = 1_200.0, hardMaxMeters = 2_000.0)
        val CITY_EXPLORATION_RADIUS = MissionRadiusPolicy(preferredMeters = 5_000.0, hardMaxMeters = 10_000.0)
        val HIDDEN_GEM_RADIUS = MissionRadiusPolicy(preferredMeters = 3_000.0, hardMaxMeters = 6_000.0)
        val LANDMARK_RADIUS = MissionRadiusPolicy(preferredMeters = 3_000.0, hardMaxMeters = 8_000.0)

        val LOCAL_FALLBACK_TARGU_JIU = listOf(
            "Parcul Central Targu Jiu",
            "Masa Tacerii",
            "Poarta Sarutului",
            "Coloana Infinitului",
            "Muzeul Judetean Gorj",
            "Parcul Coloanei Infinitului"
        )

        fun deduplicateCandidates(candidates: List<MissionPlaceCandidate>): List<MissionPlaceCandidate> {
            val kept = mutableListOf<MissionPlaceCandidate>()
            candidates.sortedByDescending { it.score }.forEach { candidate ->
                val duplicateIndex = kept.indexOfFirst { existing -> existing.isDuplicateOf(candidate) }
                if (duplicateIndex < 0) {
                    kept += candidate
                } else if (candidate.score > kept[duplicateIndex].score) {
                    kept[duplicateIndex] = candidate
                }
            }
            return kept
        }

        private fun MissionPlaceCandidate.isDuplicateOf(other: MissionPlaceCandidate): Boolean {
            if (!placeId.isNullOrBlank() && placeId == other.placeId) return true
            val name = MissionCandidateParser.normalize(this.name)
            val otherName = MissionCandidateParser.normalize(other.name)
            if (name.isBlank() || name != otherName) return false
            if (lat.round4() == other.lat.round4() && lng.round4() == other.lng.round4()) return true
            return distanceMeters(lat, lng, other.lat, other.lng) <= 75.0
        }

        private fun Double.round4(): Int = (this * 10_000).roundToInt()

        private fun distanceMeters(
            userLat: Double,
            userLng: Double,
            placeLat: Double,
            placeLng: Double
        ): Double {
            val earthRadiusMeters = 6_371_000.0
            val dLat = Math.toRadians(placeLat - userLat)
            val dLng = Math.toRadians(placeLng - userLng)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(userLat)) *
                cos(Math.toRadians(placeLat)) *
                sin(dLng / 2).let { it * it }
            return earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

private object NoopGeminiQueryTextClient : GeminiQueryTextClient {
    override suspend fun generateQueryJson(prompt: String, systemInstruction: String): String {
        return """{"search_queries":[]}"""
    }
}
