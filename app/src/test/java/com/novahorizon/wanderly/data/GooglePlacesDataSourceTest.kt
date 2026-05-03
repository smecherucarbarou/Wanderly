package com.novahorizon.wanderly.data

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
class GooglePlacesDataSourceTest {

    @Test
    fun `server error retries with bounded exponential backoff before success`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val dataSource = GooglePlacesDataSource(
            searchText = { _, _ ->
                attempts++
                if (attempts < 3) {
                    NetworkResult.HttpError(500, "proxy failure")
                } else {
                    NetworkResult.Success(singlePlaceResponse())
                }
            },
            retryDelay = { delays += it },
            queryBuilder = { listOf("cafe in $it") }
        )

        val result = dataSource.fetchHiddenGemCandidatesResult(
            userLat = 44.4268,
            userLng = 26.1025,
            radiusMeters = 2500,
            city = "Bucharest"
        )

        assertTrue(result is HiddenGemCandidateResult.Success)
        assertEquals(3, attempts)
        assertEquals(listOf(500L, 1_000L), delays)
        assertEquals("Retry Cafe", (result as HiddenGemCandidateResult.Success).candidates.single().name)
    }

    @Test
    fun `server error returns typed error after max attempts`() = runTest {
        var attempts = 0
        val dataSource = GooglePlacesDataSource(
            searchText = { _, _ ->
                attempts++
                NetworkResult.HttpError(500, "proxy failure")
            },
            retryDelay = {},
            queryBuilder = { listOf("cafe in $it") }
        )

        val result = dataSource.fetchHiddenGemCandidatesResult(
            userLat = 44.4268,
            userLng = 26.1025,
            radiusMeters = 2500,
            city = "Bucharest"
        )

        assertTrue(result is HiddenGemCandidateResult.Error)
        val error = result as HiddenGemCandidateResult.Error
        assertEquals(HiddenGemCandidateResult.Reason.Server, error.reason)
        assertEquals(500, error.statusCode)
        assertEquals(3, attempts)
    }

    @Test
    fun `bad request fails fast without retry`() = runTest {
        var attempts = 0
        val dataSource = GooglePlacesDataSource(
            searchText = { _, _ ->
                attempts++
                NetworkResult.HttpError(400, "bad request")
            },
            retryDelay = {},
            queryBuilder = { listOf("cafe in $it") }
        )

        val result = dataSource.fetchHiddenGemCandidatesResult(
            userLat = 44.4268,
            userLng = 26.1025,
            radiusMeters = 2500,
            city = "Bucharest"
        )

        assertTrue(result is HiddenGemCandidateResult.Error)
        assertEquals(HiddenGemCandidateResult.Reason.BadRequest, (result as HiddenGemCandidateResult.Error).reason)
        assertEquals(1, attempts)
    }

    @Test
    fun `unauthorized is not retried by data source`() = runTest {
        var attempts = 0
        val dataSource = GooglePlacesDataSource(
            searchText = { _, _ ->
                attempts++
                NetworkResult.HttpError(401, "unauthorized")
            },
            retryDelay = {},
            queryBuilder = { listOf("cafe in $it") }
        )

        val result = dataSource.fetchHiddenGemCandidatesResult(
            userLat = 44.4268,
            userLng = 26.1025,
            radiusMeters = 2500,
            city = "Bucharest"
        )

        assertTrue(result is HiddenGemCandidateResult.Error)
        assertEquals(HiddenGemCandidateResult.Reason.Unauthorized, (result as HiddenGemCandidateResult.Error).reason)
        assertEquals(1, attempts)
    }

    private fun singlePlaceResponse(): JSONObject =
        JSONObject(
            """
            {
              "places": [
                {
                  "displayName": { "text": "Retry Cafe" },
                  "location": { "latitude": 44.4269, "longitude": 26.1026 },
                  "formattedAddress": "Retry Street, Bucharest",
                  "types": ["cafe"],
                  "primaryType": "cafe",
                  "businessStatus": "OPERATIONAL",
                  "rating": 4.7,
                  "userRatingCount": 52
                }
              ]
            }
            """.trimIndent()
        )
}
