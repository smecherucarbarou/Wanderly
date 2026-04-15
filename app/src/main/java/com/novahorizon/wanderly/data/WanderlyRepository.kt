package com.novahorizon.wanderly.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

class WanderlyRepository(val context: Context) {

    private val _currentProfile = MutableStateFlow<Profile?>(null)
    val currentProfile: StateFlow<Profile?> = _currentProfile.asStateFlow()

    private val prefs: SharedPreferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    
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

    suspend fun getCurrentProfile(): Profile? = withContext(Dispatchers.IO) {
        try {
            var session = SupabaseClient.client.auth.currentSessionOrNull()
            
            if (session == null) {
                Log.d("WanderlyRepo", "Session null, waiting for Auth to initialize...")
                withTimeoutOrNull(5000) {
                    SupabaseClient.client.auth.sessionStatus.first { 
                        it is SessionStatus.Authenticated || it is SessionStatus.NotAuthenticated 
                    }
                }
                session = SupabaseClient.client.auth.currentSessionOrNull()
            }

            if (session == null) {
                Log.w("WanderlyRepo", "Auth session not found after waiting.")
                return@withContext null
            }

            val userId = session.user!!.id
            
            var profile = SupabaseClient.client.postgrest[Constants.TABLE_PROFILES]
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<Profile>()
            
            val userEmail = session.user?.email
                
            if (profile == null) {
                val userMetadata = session.user?.userMetadata
                val signupUsername = userMetadata?.get("username")?.toString()?.replace("\"", "")
                val defaultName = signupUsername ?: userEmail?.substringBefore("@") ?: "Explorer"
                
                val newProfile = Profile(
                    id = userId, 
                    username = defaultName,
                    honey = 0,
                    hive_rank = 1,
                    friend_code = UUID.randomUUID().toString().substring(0, 6).uppercase(),
                    streak_count = 0
                )
                SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].upsert(newProfile)
                profile = newProfile
            }
            Log.d("WanderlyRepo", "Profile loaded from Supabase: $profile")
            
            _currentProfile.value = profile
            profile
        } catch (e: Exception) {
            Log.e("WanderlyRepository", "Error getting profile: ${e.message}", e)
            null
        }
    }

    suspend fun updateProfile(profile: Profile): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("WanderlyRepo", "Attempting update for user ${profile.id}: $profile")

            // FIX-UL E AICI: Folosim .update() cu filtru în loc de .upsert()
            SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].update(profile) {
                filter { eq("id", profile.id) }
            }

            _currentProfile.value = profile
            Log.d("WanderlyRepo", "Update successful in repository")
            true
        } catch (e: Exception) {
            Log.e("WanderlyRepository", "CRITICAL: Update Profile Failed! Error: ${e.message}", e)
            false
        }
    }

    suspend fun getLeaderboard(): List<Profile> = withContext(Dispatchers.IO) {
        try {
            val session = SupabaseClient.client.auth.currentSessionOrNull() ?: return@withContext emptyList()
            val currentUserId = session.user!!.id

            val friendships = SupabaseClient.client.postgrest["friendships"]
                .select {
                    filter {
                        or {
                            eq("user_id", currentUserId)
                            eq("friend_id", currentUserId)
                        }
                    }
                }
                .decodeList<Friendship>()

            val relevantIds = friendships.map { 
                if (it.user_id == currentUserId) it.friend_id else it.user_id 
            }.toMutableList()
            relevantIds.add(currentUserId)

            SupabaseClient.client.postgrest[Constants.TABLE_PROFILES]
                .select {
                    filter {
                        isIn("id", relevantIds)
                    }
                    order("honey", Order.DESCENDING)
                }
                .decodeList<Profile>()
        } catch (e: Exception) {
            Log.e("WanderlyRepository", "Error getting leaderboard", e)
            emptyList()
        }
    }

    suspend fun addFriendByCode(friendCode: String): String = withContext(Dispatchers.IO) {
        try {
            val session = SupabaseClient.client.auth.currentSessionOrNull() ?: return@withContext "Not authenticated"
            val currentUserId = session.user!!.id

            val targetUsers = SupabaseClient.client.postgrest[Constants.TABLE_PROFILES]
                .select { filter { ilike("friend_code", friendCode) } }
                .decodeList<Profile>()
                
            if (targetUsers.isEmpty()) return@withContext "Friend code not found"
            
            val friendId = targetUsers.first().id
            if (friendId == currentUserId) return@withContext "You cannot add yourself"

            SupabaseClient.client.postgrest["friendships"].insert(Friendship(user_id = currentUserId, friend_id = friendId))
            "Friend added successfully!"
        } catch (e: Exception) {
            if (e.message?.contains("duplicate key") == true || e.message?.contains("unique constraint") == true) {
                "Already friends with this user"
            } else {
                "Failed to add friend: ${e.message}"
            }
        }
    }

    suspend fun removeFriend(friendId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = SupabaseClient.client.auth.currentSessionOrNull() ?: return@withContext false
            val currentUserId = session.user!!.id

            // Use OR condition to catch both directions in one call
            SupabaseClient.client.postgrest["friendships"].delete {
                filter {
                    or {
                        and {
                            eq("user_id", currentUserId)
                            eq("friend_id", friendId)
                        }
                        and {
                            eq("user_id", friendId)
                            eq("friend_id", currentUserId)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e("WanderlyRepository", "Error removing friend: ${e.message}", e)
            false
        }
    }

    suspend fun getFriends(): List<Profile> = withContext(Dispatchers.IO) {
        try {
            val session = SupabaseClient.client.auth.currentSessionOrNull() ?: return@withContext emptyList()
            val currentUserId = session.user!!.id

            val friendships = SupabaseClient.client.postgrest["friendships"]
                .select {
                    filter {
                        or {
                            eq("user_id", currentUserId)
                            eq("friend_id", currentUserId)
                        }
                    }
                }
                .decodeList<Friendship>()

            if (friendships.isEmpty()) return@withContext emptyList()

            val friendIds = friendships.map { 
                if (it.user_id == currentUserId) it.friend_id else it.user_id 
            }

            SupabaseClient.client.postgrest[Constants.TABLE_PROFILES]
                .select {
                    filter {
                        isIn("id", friendIds)
                    }
                }
                .decodeList<Profile>()

        } catch (e: Exception) {
            Log.e("WanderlyRepository", "Error getting friends", e)
            emptyList()
        }
    }

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
                Log.e("WanderlyRepository", "Overpass error: ${e.message}")
            }
        }
        emptyList()
    }

    fun getCachedUsername(): String? = prefs.getString(Constants.KEY_USERNAME, null)
    fun cacheUsername(username: String) { prefs.edit().putString(Constants.KEY_USERNAME, username).apply() }
    fun getLastVisitDate(): String? = prefs.getString(Constants.KEY_LAST_VISIT, "")
    fun updateLastVisitDate(date: String) { prefs.edit().putString(Constants.KEY_LAST_VISIT, date).apply() }
    
    suspend fun resetMissionDateForTesting(): Boolean = withContext(Dispatchers.IO) {
        try {
            val profile = getCurrentProfile() ?: return@withContext false
            val updated = profile.copy(last_mission_date = "2000-01-01")
            updateProfile(updated)
        } catch (e: Exception) {
            false
        }
    }
    fun getMissionHistory(): String = prefs.getString(Constants.KEY_MISSION_HISTORY, "") ?: ""
    fun getMissionTarget(): String? = prefs.getString(Constants.KEY_MISSION_TARGET, null)
    fun getMissionCity(): String? = prefs.getString(Constants.KEY_MISSION_CITY, null)

    fun saveMissionData(text: String, target: String, history: String, city: String?) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Calendar.getInstance().time)
        updateLastVisitDate(today)

        prefs.edit()
            .putString(Constants.KEY_MISSION_TEXT, text)
            .putString(Constants.KEY_MISSION_TARGET, target)
            .putString(Constants.KEY_MISSION_CITY, city)
            .putString(Constants.KEY_MISSION_HISTORY, history)
            .apply()
    }
}
