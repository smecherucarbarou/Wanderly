package com.novahorizon.wanderly.data.mission

import android.app.Application
import com.novahorizon.wanderly.api.NetworkResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GooglePlacesSearchServiceTest {

    @Test
    fun `retries retryable server errors before returning success`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val service = GooglePlacesSearchService(
            searchText = { _, _ ->
                attempts++
                if (attempts < 3) {
                    NetworkResult.HttpError(500, "server error")
                } else {
                    NetworkResult.Success(singlePlaceResponse())
                }
            },
            retryDelay = { delays += it }
        )

        val result = service.searchTextResult("park in Bucharest", 44.42, 26.10, 3_000)

        assertTrue(result is PlacesSearchResult.Success)
        assertEquals(3, attempts)
        assertEquals(listOf(500L, 1_000L), delays)
        assertEquals("Retry Park", (result as PlacesSearchResult.Success).places.single().placesName)
    }

    @Test
    fun `does not retry unauthorized because proxy client owns token refresh`() = runTest {
        var attempts = 0
        val service = GooglePlacesSearchService(
            searchText = { _, _ ->
                attempts++
                NetworkResult.HttpError(401, "unauthorized")
            },
            retryDelay = {}
        )

        val result = service.searchTextResult("park in Bucharest")

        assertTrue(result is PlacesSearchResult.Error)
        val error = result as PlacesSearchResult.Error
        assertEquals(PlacesSearchResult.Reason.Unauthorized, error.reason)
        assertEquals(401, error.statusCode)
        assertEquals(1, attempts)
    }

    @Test
    fun `returns typed server error after retry attempts are exhausted`() = runTest {
        var attempts = 0
        val service = GooglePlacesSearchService(
            searchText = { _, _ ->
                attempts++
                NetworkResult.HttpError(503, "unavailable")
            },
            retryDelay = {}
        )

        val result = service.searchTextResult("park in Bucharest")

        assertTrue(result is PlacesSearchResult.Error)
        val error = result as PlacesSearchResult.Error
        assertEquals(PlacesSearchResult.Reason.Server, error.reason)
        assertEquals(503, error.statusCode)
        assertEquals(3, attempts)
    }

    private fun singlePlaceResponse(): JSONObject =
        JSONObject(
            """
            {
              "places": [
                {
                  "id": "places/retry-park",
                  "displayName": { "text": "Retry Park" },
                  "location": { "latitude": 44.4269, "longitude": 26.1026 },
                  "formattedAddress": "Retry Street, Bucharest",
                  "types": ["park"],
                  "primaryType": "park",
                  "businessStatus": "OPERATIONAL",
                  "rating": 4.7,
                  "userRatingCount": 52
                }
              ]
            }
            """.trimIndent()
        )
}
