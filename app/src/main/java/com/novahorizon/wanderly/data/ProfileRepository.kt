package com.novahorizon.wanderly.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class ProfileRepository(
    private val context: Context,
    private val preferencesStore: PreferencesStore
) {
    internal data class ClientProfileUpdate(
        val username: String?,
        val honey: Int?,
        val hive_rank: Int?,
        val badges: List<String>?,
        val cities_visited: List<String>?,
        val avatar_url: String?,
        val last_mission_date: String?,
        val last_lat: Double?,
        val last_lng: Double?,
        val friend_code: String?,
        val streak_count: Int?,
        val explorer_class: String?
    )

    private val _currentProfile = MutableStateFlow<Profile?>(null)
    val currentProfile: StateFlow<Profile?> = _currentProfile.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getCurrentProfile(): Profile? = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: run {
                Log.w("ProfileRepository", "Auth session not found after waiting.")
                return@withContext null
            }

            val userId = session.user!!.id
            val loadedProfile = SupabaseClient.client.postgrest[Constants.TABLE_PROFILES]
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<Profile>()

            var profile = loadedProfile
            val userEmail = session.user?.email

            if (profile == null) {
                val userMetadata = session.user?.userMetadata
                val signupUsername = userMetadata?.get("username")?.toString()?.replace("\"", "")
                val defaultName = signupUsername ?: userEmail?.substringBefore("@") ?: "Explorer"

                val newProfile = Profile(
                    id = userId,
                    username = defaultName,
                    honey = 0,
                    hive_rank = HiveRank.fromHoney(0),
                    friend_code = UUID.randomUUID().toString().substring(0, 6).uppercase(),
                    streak_count = 0
                )
                SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].upsert(newProfile)
                profile = newProfile
            }

            profile = normalizeProfile(profile)
            if (loadedProfile != null && loadedProfile.hive_rank != profile.hive_rank) {
                persistProfile(profile)
            }

            _currentProfile.value = profile
            profile
        } catch (e: Exception) {
            Log.e("ProfileRepository", "Error getting profile: ${e.message}", e)
            null
        }
    }

    suspend fun updateProfile(profile: Profile): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalizedProfile = normalizeProfile(profile)
            persistProfile(normalizedProfile)
            _currentProfile.value = normalizedProfile
            true
        } catch (e: Exception) {
            Log.e("ProfileRepository", "Update profile failed: ${e.message}", e)
            false
        }
    }

    suspend fun resetMissionDateForTesting(): Boolean = withContext(Dispatchers.IO) {
        try {
            val profile = getCurrentProfile() ?: return@withContext false
            updateProfile(profile.copy(last_mission_date = "2000-01-01"))
        } catch (_: Exception) {
            false
        }
    }

    suspend fun uploadAvatar(uri: Uri, profileId: String): String? = withContext(Dispatchers.IO) {
        val auth = SupabaseClient.client.auth
        val accessToken = auth.currentAccessTokenOrNull() ?: run {
            Log.e("ProfileRepository", "No access token available for avatar upload")
            return@withContext null
        }
        
        Log.d("ProfileRepository", "Uploading avatar for profile: $profileId")
        
        try {
            val avatarBytes = buildAvatarBytes(uri) ?: run {
                Log.e("ProfileRepository", "Could not read image bytes from: $uri")
                return@withContext null
            }
            
            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
            val bucket = Constants.STORAGE_BUCKET_AVATARS
            val filePath = "profiles/$profileId/avatar.jpg"
            val uploadUrl = "$baseUrl/storage/v1/object/$bucket/$filePath"
            
            Log.d("ProfileRepository", "Target upload URL: $uploadUrl")

            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("x-upsert", "true")
                .post(avatarBytes.toRequestBody("image/jpeg".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e("ProfileRepository", "Avatar upload failed. Code: ${response.code}, Response: $responseBody")
                    // If we get 401/403 here, we could retry, but user says 401 is fixed.
                    // 400 usually means bucket issue or path issue.
                    return@withContext null
                }
                Log.d("ProfileRepository", "Avatar upload successful for $profileId")
            }

            val version = System.currentTimeMillis()
            val finalUrl = "$baseUrl/storage/v1/object/public/$bucket/$filePath?v=$version"
            Log.d("ProfileRepository", "Generated avatar URL: $finalUrl")
            finalUrl
        } catch (e: Exception) {
            Log.e("ProfileRepository", "Exception during avatar upload", e)
            null
        }
    }

    fun onVisitDateUpdated(date: String) {
        preferencesStore.updateLastVisitDate(date)
    }

    private fun normalizeProfile(profile: Profile): Profile = profile.withDerivedHiveRank()

    private suspend fun persistProfile(profile: Profile) {
        val payload = toClientProfileUpdate(profile)

        // Use an explicit PATCH payload so values like streak_count = 0 are not skipped.
        SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].update({
            Profile::username setTo payload.username
            Profile::honey setTo payload.honey
            Profile::hive_rank setTo payload.hive_rank
            Profile::badges setTo payload.badges
            Profile::cities_visited setTo payload.cities_visited
            Profile::avatar_url setTo payload.avatar_url
            Profile::last_mission_date setTo payload.last_mission_date
            Profile::last_lat setTo payload.last_lat
            Profile::last_lng setTo payload.last_lng
            Profile::friend_code setTo payload.friend_code
            Profile::streak_count setTo payload.streak_count
            Profile::explorer_class setTo payload.explorer_class
        }) {
            filter { eq("id", profile.id) }
        }
    }

    private fun buildAvatarBytes(uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, bounds)
        } ?: return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqWidth = 512, reqHeight = 512)
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        } ?: return null

        return ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            outputStream.toByteArray()
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    companion object {
        internal fun toClientProfileUpdate(profile: Profile): ClientProfileUpdate {
            return ClientProfileUpdate(
                username = profile.username,
                honey = profile.honey,
                hive_rank = profile.hive_rank,
                badges = profile.badges,
                cities_visited = profile.cities_visited,
                avatar_url = profile.avatar_url,
                last_mission_date = profile.last_mission_date,
                last_lat = profile.last_lat,
                last_lng = profile.last_lng,
                friend_code = profile.friend_code,
                streak_count = profile.streak_count,
                explorer_class = profile.explorer_class
            )
        }
    }
}
