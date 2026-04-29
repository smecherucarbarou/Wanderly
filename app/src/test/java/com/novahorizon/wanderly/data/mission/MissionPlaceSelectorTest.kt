package com.novahorizon.wanderly.data.mission

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionPlaceSelectorTest {

    @Test
    fun `does not stop after first invalid candidate and selects second valid candidate`() = runTest {
        val search = FakeMissionPlaceSearchService(
            mapOf(
                "Too Far Monument Targu Jiu Romania" to listOf(
                    place(name = "Too Far Monument", latitude = 45.50, longitude = 23.27)
                ),
                "Central Park Targu Jiu Romania" to listOf(
                    place(name = "Central Park", latitude = 45.036, longitude = 23.276)
                )
            )
        )
        val selector = MissionPlaceSelector(search)

        val result = selector.selectBestMissionPlace(
            userLat = 45.034,
            userLng = 23.274,
            city = "Targu Jiu",
            countryRegion = "Romania",
            missionType = "landmark",
            candidates = listOf(
                MissionPlaceCandidate(name = "Too Far Monument", query = "Too Far Monument Targu Jiu Romania"),
                MissionPlaceCandidate(name = "Central Park", query = "Central Park Targu Jiu Romania")
            )
        )

        val success = result as MissionPlaceSelectionResult.Success
        assertEquals("Central Park", success.place.placesName)
        assertTrue(search.queries.size > 1)
    }

    @Test
    fun `accepts same city landmark within five kilometers`() = runTest {
        val search = FakeMissionPlaceSearchService(
            mapOf(
                "The Infinity Column Targu Jiu Romania" to listOf(
                    place(
                        name = "The Infinity Column",
                        latitude = 45.061,
                        longitude = 23.274,
                        types = setOf("tourist_attraction", "historical_landmark")
                    )
                )
            )
        )
        val selector = MissionPlaceSelector(search)

        val result = selector.selectBestMissionPlace(
            userLat = 45.034,
            userLng = 23.274,
            city = "Targu Jiu",
            countryRegion = "Romania",
            missionType = "landmark",
            candidates = listOf(
                MissionPlaceCandidate(
                    name = "The Infinity Column",
                    query = "The Infinity Column Targu Jiu Romania",
                    category = "monument"
                )
            )
        )

        val success = result as MissionPlaceSelectionResult.Success
        assertEquals("The Infinity Column", success.place.placesName)
        assertTrue(success.place.distanceMeters > MissionPlaceSelector.LANDMARK_RADIUS.preferredMeters)
        assertTrue(success.place.distanceMeters <= MissionPlaceSelector.LANDMARK_RADIUS.hardMaxMeters)
    }

    @Test
    fun `accepts translated same city landmark when google name differs from local query`() = runTest {
        val search = FakeMissionPlaceSearchService(
            mapOf(
                "Coloana Infinitului Targu Jiu Romania" to listOf(
                    place(
                        name = "The Infinity Column",
                        latitude = 45.061,
                        longitude = 23.274,
                        types = setOf("tourist_attraction", "historical_landmark"),
                        rating = 4.8,
                        userRatingsTotal = 500
                    )
                )
            )
        )
        val selector = MissionPlaceSelector(search)

        val result = selector.selectBestMissionPlace(
            userLat = 45.034,
            userLng = 23.274,
            city = "Targu Jiu",
            countryRegion = "Romania",
            missionType = "landmark",
            candidates = listOf(
                MissionPlaceCandidate(
                    name = "Coloana Infinitului",
                    localName = "Coloana Infinitului",
                    query = "Coloana Infinitului Targu Jiu Romania",
                    category = "monument"
                )
            )
        )

        val success = result as MissionPlaceSelectionResult.Success
        assertEquals("The Infinity Column", success.place.placesName)
    }

    @Test
    fun `city mismatch is a scoring penalty not a hard rejection`() = runTest {
        val search = FakeMissionPlaceSearchService(
            mapOf(
                "Central Park Targu Jiu Romania" to listOf(
                    place(
                        name = "Central Park",
                        latitude = 45.036,
                        longitude = 23.276,
                        locality = "Other City",
                        formattedAddress = "Other City, Romania"
                    )
                )
            )
        )
        val selector = MissionPlaceSelector(search)

        val result = selector.selectBestMissionPlace(
            userLat = 45.034,
            userLng = 23.274,
            city = "Targu Jiu",
            countryRegion = "Romania",
            missionType = "landmark",
            candidates = listOf(MissionPlaceCandidate(name = "Central Park", query = "Central Park Targu Jiu Romania"))
        )

        val success = result as MissionPlaceSelectionResult.Success
        assertEquals("Central Park", success.place.placesName)
    }

    @Test
    fun `uses deterministic places fallback before generic fallback`() = runTest {
        val search = FakeMissionPlaceSearchService(
            mapOf(
                "parks in Targu Jiu" to listOf(
                    place(name = "Parcul Central", latitude = 45.035, longitude = 23.275, types = setOf("park"))
                )
            )
        )
        val selector = MissionPlaceSelector(search)

        val result = selector.selectBestMissionPlace(
            userLat = 45.034,
            userLng = 23.274,
            city = "Targu Jiu",
            countryRegion = "Romania",
            missionType = "landmark",
            candidates = emptyList()
        )

        val success = result as MissionPlaceSelectionResult.Success
        assertEquals("Parcul Central", success.place.placesName)
        assertTrue(search.queries.contains("parks in Targu Jiu"))
    }

    @Test
    fun `caps total provider queries`() = runTest {
        val search = FakeMissionPlaceSearchService(emptyMap())
        val selector = MissionPlaceSelector(search)

        selector.selectBestMissionPlace(
            userLat = 45.034,
            userLng = 23.274,
            city = "Targu Jiu",
            countryRegion = "Romania",
            missionType = "landmark",
            candidates = (1..20).map { index ->
                MissionPlaceCandidate(name = "Candidate $index", query = "Candidate $index Targu Jiu Romania")
            }
        )

        assertTrue(search.queries.size <= MissionPlaceQueryBuilder.MAX_TOTAL_PLACES_QUERIES_PER_MISSION * 2)
    }

    private fun place(
        name: String,
        latitude: Double,
        longitude: Double,
        locality: String = "Targu Jiu",
        formattedAddress: String = "Targu Jiu, Romania",
        types: Set<String> = setOf("tourist_attraction"),
        rating: Double? = 4.5,
        userRatingsTotal: Int? = 20
    ): PlacesMissionSearchResult = PlacesMissionSearchResult(
        placesName = name,
        placesId = "places-$name",
        latitude = latitude,
        longitude = longitude,
        locality = locality,
        formattedAddress = formattedAddress,
        rating = rating,
        userRatingsTotal = userRatingsTotal,
        types = types,
        businessStatus = "OPERATIONAL"
    )

    private class FakeMissionPlaceSearchService(
        private val responses: Map<String, List<PlacesMissionSearchResult>>
    ) : MissionPlaceSearchService {
        val queries = mutableListOf<String>()

        override suspend fun searchText(query: String): List<PlacesMissionSearchResult> {
            queries += query
            return responses[query].orEmpty()
        }
    }
}
