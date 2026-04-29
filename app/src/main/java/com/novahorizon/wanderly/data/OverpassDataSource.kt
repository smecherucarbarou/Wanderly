package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.util.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class OverpassDataSource(
    private val callFactory: Call.Factory = defaultCallFactory(DEFAULT_REQUEST_TIMEOUT_MS),
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val logWarnings: Boolean = true
) {

    suspend fun fetchNearbyPlaceNames(lat: Double, lng: Double, radius: Int): List<String> = withContext(Dispatchers.IO) {
        val query = "[out:json][timeout:25];(node[\"name\"](around:$radius,$lat,$lng);way[\"name\"](around:$radius,$lat,$lng););out center 20;"
        fetchCandidates(query).map { it.name }
    }

    suspend fun fetchHiddenGemCandidates(lat: Double, lng: Double, radius: Int): List<DiscoveredPlace> = withContext(Dispatchers.IO) {
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
        fetchCandidates(query)
    }

    private suspend fun fetchCandidates(query: String): List<DiscoveredPlace> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val body = "data=$encodedQuery".toRequestBody("application/x-www-form-urlencoded".toMediaType())

        for (endpoint in endpoints.take(maxAttempts.coerceAtLeast(1))) {
            try {
                val request = Request.Builder().url(endpoint).post(body).build()
                val places = withTimeoutOrNull(requestTimeoutMs) {
                    callFactory.newCall(request).await().use(::parseCandidates)
                }
                if (places == null) {
                    logWarning("Overpass request timed out after ${requestTimeoutMs}ms")
                    continue
                }
                if (places.isNotEmpty()) {
                    return@withContext places
                        .distinctBy { it.name.lowercase() }
                        .sortedBy { it.name.lowercase() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarning("Overpass error [${e.javaClass.simpleName}: ${LogRedactor.redact(e.message)}]")
            }
        }
        emptyList()
    }

    private fun parseCandidates(response: Response): List<DiscoveredPlace> {
        val bodyString = response.body.string()
        if (bodyString.isBlank()) return emptyList()
        if (!response.isSuccessful || bodyString.trim().startsWith("<?xml")) return emptyList()

        val elements = JSONObject(bodyString).optJSONArray("elements") ?: return emptyList()
        val places = mutableListOf<DiscoveredPlace>()
        for (i in 0 until elements.length()) {
            val element = elements.getJSONObject(i)
            val tags = element.optJSONObject("tags") ?: continue
            val name = tags.optString("name", "").trim()
            if (name.isEmpty()) continue

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
                    category = CategoryMapper.fromOverpassTags(tags),
                    areaLabel = buildAreaLabel(tags),
                    source = "overpass"
                )
            )
        }
        return places
    }

    private fun buildAreaLabel(tags: JSONObject): String? {
        val street = tags.optString("addr:street").trim()
        val district = tags.optString("addr:suburb").trim()
        val neighbourhood = tags.optString("addr:neighbourhood").trim()
        return listOf(neighbourhood, district, street).firstOrNull { it.isNotBlank() }
    }

    private fun logWarning(message: String) {
        if (logWarnings && BuildConfig.DEBUG) {
            AppLogger.w("OverpassDataSource", LogRedactor.redact(message))
        }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 8_000L
        const val DEFAULT_MAX_ATTEMPTS = 2

        private val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.nchc.org.tw/api/interpreter"
        )

        private fun defaultCallFactory(timeoutMs: Long): Call.Factory {
            return OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
