package com.novahorizon.wanderly.data

import android.util.Log
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class OverpassDataSource {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.nchc.org.tw/api/interpreter"
    )

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

        for (endpoint in endpoints) {
            try {
                val request = Request.Builder().url(endpoint).post(body).build()
                client.newCall(request).await().use { response ->
                    val bodyString = response.body.string()
                    if (bodyString.isBlank()) return@use
                    if (!response.isSuccessful || bodyString.trim().startsWith("<?xml")) return@use

                    val elements = JSONObject(bodyString).optJSONArray("elements") ?: return@use
                    val places = mutableListOf<DiscoveredPlace>()
                    for (i in 0 until elements.length()) {
                        val tags = elements.getJSONObject(i).optJSONObject("tags") ?: continue
                        val name = tags.optString("name", "").trim()
                        if (name.isEmpty()) continue

                        val element = elements.getJSONObject(i)
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
                    if (places.isNotEmpty()) {
                        return@withContext places
                            .distinctBy { it.name.lowercase() }
                            .sortedBy { it.name.lowercase() }
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e("OverpassDataSource", "Overpass error [${e.javaClass.simpleName}]", e)
                }
            }
        }
        emptyList()
    }

    private fun buildAreaLabel(tags: JSONObject): String? {
        val street = tags.optString("addr:street").trim()
        val district = tags.optString("addr:suburb").trim()
        val neighbourhood = tags.optString("addr:neighbourhood").trim()
        return listOf(neighbourhood, district, street).firstOrNull { it.isNotBlank() }
    }
}
