package com.novahorizon.wanderly.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class WanderlyRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getCurrentProfile(): Profile? = withContext(Dispatchers.IO) {
        try {
            val session = SupabaseClient.client.auth.currentSessionOrNull() ?: return@withContext null
            val userId = session.user!!.id
            
            var profile = SupabaseClient.client.postgrest[Constants.TABLE_PROFILES]
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<Profile>()
                
            if (profile == null) {
                val userMetadata = session.user?.userMetadata
                val signupUsername = userMetadata?.get("username")?.toString()?.replace("\"", "")
                val defaultName = signupUsername ?: session.user?.email?.substringBefore("@") ?: "Explorer"
                
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
            profile
        } catch (e: Exception) {
            Log.e("WanderlyRepository", "Error getting profile", e)
            null
        }
    }

    suspend fun updateProfile(profile: Profile): Boolean = withContext(Dispatchers.IO) {
        try {
            SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].upsert(profile)
            true
        } catch (e: Exception) {
            Log.e("WanderlyRepository", "Error updating profile. Check if 'streak_count' column exists in Supabase table!", e)
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
        try {
            val query = "[out:json];(node[\"name\"](around:$radius,$lat,$lng);way[\"name\"](around:$radius,$lat,$lng););out center 20;"
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
            val body = "data=$query".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val request = Request.Builder().url("https://overpass-api.interpretation.be/api/interpreter").post(body).build()
            val response = client.newCall(request).execute()
            val bodyString = response.body?.string() ?: return@withContext emptyList()
            val elements = JSONObject(bodyString).getJSONArray("elements")
            val places = mutableListOf<String>()
            for (i in 0 until elements.length()) {
                val tags = elements.getJSONObject(i).optJSONObject("tags") ?: continue
                val name = tags.optString("name", "").trim()
                if (name.isNotEmpty()) places.add(name)
            }
            places.distinct()
        } catch (e: Exception) { 
            Log.e("WanderlyRepository", "Error fetching places", e)
            emptyList() 
        }
    }

    fun getCachedUsername(): String? = prefs.getString(Constants.KEY_USERNAME, null)
    fun cacheUsername(username: String) { prefs.edit().putString(Constants.KEY_USERNAME, username).apply() }
    fun getLastVisitDate(): String? = prefs.getString(Constants.KEY_LAST_VISIT, "")
    fun updateLastVisitDate(date: String) { prefs.edit().putString(Constants.KEY_LAST_VISIT, date).apply() }
    fun getMissionHistory(): String = prefs.getString(Constants.KEY_MISSION_HISTORY, "") ?: ""
    fun getMissionTarget(): String? = prefs.getString(Constants.KEY_MISSION_TARGET, null)

    fun saveMissionData(text: String, target: String, history: String) {
        prefs.edit()
            .putString(Constants.KEY_MISSION_TEXT, text)
            .putString(Constants.KEY_MISSION_TARGET, target)
            .putString(Constants.KEY_MISSION_HISTORY, history)
            .apply()
    }
}
