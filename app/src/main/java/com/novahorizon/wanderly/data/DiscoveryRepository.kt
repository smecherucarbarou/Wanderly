package com.novahorizon.wanderly.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DiscoveryRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val overpassEndpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.nchc.org.tw/api/interpreter"
    )

    suspend fun fetchNearbyPlaces(lat: Double, lng: Double, radius: Int): List<String> = withContext(Dispatchers.IO) {
        val query = "[out:json][timeout:25];(node[\"name\"](around:$radius,$lat,$lng);way[\"name\"](around:$radius,$lat,$lng););out center 20;"
        fetchFromOverpass(query)
    }

    suspend fun fetchHiddenGems(lat: Double, lng: Double, radius: Int): List<String> = withContext(Dispatchers.IO) {
        val query = """
            [out:json][timeout:25];
            (
              node["amenity"~"cafe|pub|bar|community_centre"](around:$radius,$lat,$lng);
              node["historic"](around:$radius,$lat,$lng);
              node["tourism"~"viewpoint|artwork|attraction"](around:$radius,$lat,$lng);
              node["leisure"~"park|garden|playground"](around:$radius,$lat,$lng);
            );
            out center 15;
        """.trimIndent()
        fetchFromOverpass(query)
    }

    private suspend fun fetchFromOverpass(query: String): List<String> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val body = "data=$encodedQuery".toRequestBody("application/x-www-form-urlencoded".toMediaType())

        for (endpoint in overpassEndpoints) {
            try {
                val request = Request.Builder().url(endpoint).post(body).build()
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: return@use
                    if (!response.isSuccessful || bodyString.trim().startsWith("<?xml")) return@use

                    val elements = JSONObject(bodyString).optJSONArray("elements") ?: return@use
                    val places = mutableListOf<String>()
                    for (i in 0 until elements.length()) {
                        val tags = elements.getJSONObject(i).optJSONObject("tags") ?: continue
                        val name = tags.optString("name", "").trim()
                        if (name.isNotEmpty()) places.add(name)
                    }
                    if (places.isNotEmpty()) return@withContext places.distinct()
                }
            } catch (e: Exception) {
                Log.e("DiscoveryRepository", "Overpass error: ${e.message}")
            }
        }
        emptyList()
    }
}
