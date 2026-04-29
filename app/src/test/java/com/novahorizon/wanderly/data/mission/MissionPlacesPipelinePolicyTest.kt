package com.novahorizon.wanderly.data.mission

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionPlacesPipelinePolicyTest {

    @Test
    fun `deterministic query provider returns at least twelve city queries`() {
        val queries = DeterministicMissionQueryProvider().getQueries("Targu Jiu")

        assertTrue(queries.size >= 12)
        assertTrue(queries.all { it.contains("Targu Jiu") })
        assertTrue(queries.any { it.contains("parks", ignoreCase = true) })
        assertTrue(queries.any { it.contains("museums", ignoreCase = true) })
        assertTrue(queries.any { it.contains("hidden gems", ignoreCase = true) })
    }

    @Test
    fun `school candidate is hard rejected`() {
        val filter = MissionPlaceFilter()
        val school = candidate(
            name = "Liceul X",
            types = listOf("school", "establishment"),
            distanceMeters = 500.0
        )

        val (accepted, rejected) = filter.filter(listOf(school), 8_000.0, "Targu Jiu")

        assertTrue(accepted.isEmpty())
        assertEquals("unsafe_type_school", rejected.single().rejectionReason)
    }

    @Test
    fun `hospital candidate is hard rejected`() {
        val filter = MissionPlaceFilter()
        val hospital = candidate(
            name = "Spitalul Judetean",
            types = listOf("hospital", "establishment"),
            distanceMeters = 700.0
        )

        val (accepted, rejected) = filter.filter(listOf(hospital), 8_000.0, "Targu Jiu")

        assertTrue(accepted.isEmpty())
        assertEquals("unsafe_type_hospital", rejected.single().rejectionReason)
    }

    @Test
    fun `candidate with missing city but valid distance is not hard rejected`() {
        val filter = MissionPlaceFilter()
        val unknownCity = candidate(city = null, distanceMeters = 900.0)

        val (accepted, rejected) = filter.filter(listOf(unknownCity), 8_000.0, "Targu Jiu")

        assertEquals(listOf(unknownCity), accepted)
        assertTrue(rejected.isEmpty())
    }

    @Test
    fun `blocked keyword candidate is rejected even when place type is generic`() {
        val filter = MissionPlaceFilter()
        val blocked = candidate(
            name = "Central Residence",
            types = listOf("point_of_interest", "establishment"),
            distanceMeters = 450.0
        )

        val (accepted, rejected) = filter.filter(listOf(blocked), 8_000.0, "Targu Jiu")

        assertTrue(accepted.isEmpty())
        assertEquals("blocked_keyword_residence", rejected.single().rejectionReason)
    }

    @Test
    fun `candidate inside one kilometer outranks far candidate when both are valid`() {
        val scorer = MissionPlaceScorer()
        val near = candidate(name = "Near Monument", distanceMeters = 650.0)
        val far = candidate(name = "Far Monument", distanceMeters = 7_000.0)

        assertTrue(scorer.score(near, "Targu Jiu") > scorer.score(far, "Targu Jiu"))
    }

    @Test
    fun `duplicate place id candidates are deduplicated keeping highest score`() {
        val low = candidate(placeId = "abc", name = "Central Park", score = 12.0)
        val high = candidate(placeId = "abc", name = "Central Park", rating = 4.9, score = 40.0)

        val deduped = MissionPlaceSelector.deduplicateCandidates(listOf(low, high))

        assertEquals(1, deduped.size)
        assertEquals(4.9, deduped.single().rating ?: 0.0, 0.0)
    }

    @Test
    fun `same normalized name within seventy five meters is deduplicated`() {
        val first = candidate(placeId = null, name = "Masa Tacerii", lat = 45.0340, lng = 23.2740, score = 10.0)
        val second = candidate(placeId = null, name = "Masa Tăcerii", lat = 45.0342, lng = 23.2742, score = 20.0)

        val deduped = MissionPlaceSelector.deduplicateCandidates(listOf(first, second))

        assertEquals(1, deduped.size)
        assertEquals("Masa Tăcerii", deduped.single().name)
    }

    @Test
    fun `empty hard radius places response triggers emergency radius retry`() = runTest {
        val fetcher = FakeCandidateFetcher(
            hardRadiusCandidates = emptyList(),
            emergencyCandidates = listOf(candidate(name = "Emergency Park", distanceMeters = 9_500.0))
        )
        val selector = MissionPlaceSelector(
            geminiQueryProvider = GeminiMissionQueryProvider(FakeGeminiQueryClient(emptyList())),
            deterministicQueryProvider = DeterministicMissionQueryProvider(),
            candidateFetcher = fetcher,
            filter = MissionPlaceFilter(),
            scorer = MissionPlaceScorer()
        )

        val result = selector.selectMissionPlace(45.034, 23.274, "Targu Jiu")

        val success = result as MissionPlaceResult.Success
        assertEquals("Emergency Park", success.place.name)
        assertEquals(listOf(8_000, 12_000), fetcher.radii)
    }

    private fun candidate(
        placeId: String? = "place-1",
        name: String = "Central Monument",
        lat: Double = 45.034,
        lng: Double = 23.274,
        city: String? = "Targu Jiu",
        types: List<String> = listOf("tourist_attraction", "point_of_interest"),
        rating: Double? = 4.5,
        userRatingsTotal: Int? = 200,
        distanceMeters: Double = 650.0,
        score: Double = 0.0
    ): MissionPlaceCandidate = MissionPlaceCandidate(
        placeId = placeId,
        name = name,
        lat = lat,
        lng = lng,
        address = "$city, Romania",
        city = city,
        types = types,
        rating = rating,
        userRatingsTotal = userRatingsTotal,
        openNow = true,
        distanceMeters = distanceMeters,
        source = CandidateSource.GOOGLE_PLACES,
        query = "landmarks in ${city ?: "nearby"}",
        score = score
    )

    private class FakeCandidateFetcher(
        private val hardRadiusCandidates: List<MissionPlaceCandidate>,
        private val emergencyCandidates: List<MissionPlaceCandidate>
    ) : GooglePlacesCandidateFetcher(FakeMissionPlaceSearchService(emptyMap())) {
        val radii = mutableListOf<Int>()

        override suspend fun fetchAllCandidates(
            queries: List<String>,
            userLat: Double,
            userLng: Double,
            radius: Int,
            source: CandidateSource
        ): List<MissionPlaceCandidate> {
            radii += radius
            return if (radius == EMERGENCY_RADIUS) emergencyCandidates else hardRadiusCandidates
        }
    }

    private class FakeGeminiQueryClient(
        private val queries: List<String>
    ) : GeminiQueryTextClient {
        override suspend fun generateQueryJson(prompt: String, systemInstruction: String): String {
            return """
                {
                  "city": "Targu Jiu",
                  "country": "Romania",
                  "search_queries": ${queries.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}
                }
            """.trimIndent()
        }
    }

    private class FakeMissionPlaceSearchService(
        private val responses: Map<String, List<PlacesMissionSearchResult>>
    ) : MissionPlaceSearchService {
        override suspend fun searchText(query: String): List<PlacesMissionSearchResult> = responses[query].orEmpty()
    }
}
