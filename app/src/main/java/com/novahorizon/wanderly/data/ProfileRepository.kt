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
                SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].update(profile) {
                    filter { eq("id", userId) }
                }
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
            SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].update(normalizedProfile) {
                filter { eq("id", normalizedProfile.id) }
            }
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
        try {
            val accessToken = SupabaseClient.client.auth.currentAccessTokenOrNull() ?: return@withContext null
            val avatarBytes = buildAvatarBytes(uri) ?: return@withContext null
            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
            val objectPath = "profiles/$profileId/avatar.jpg"
            val uploadUrl = "$baseUrl/storage/v1/object/${Constants.STORAGE_BUCKET_AVATARS}/$objectPath"

            val request = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("x-upsert", "true")
                .post(avatarBytes.toRequestBody("image/jpeg".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    Log.e("ProfileRepository", "Avatar upload failed with code=${response.code}")
                    if (errorBody.isNotBlank()) {
                        Log.d("ProfileRepository", "Avatar upload error bodyLength=${errorBody.length}")
                    }
                    return@withContext null
                }
            }

            val version = System.currentTimeMillis()
            "$baseUrl/storage/v1/object/public/${Constants.STORAGE_BUCKET_AVATARS}/$objectPath?v=$version"
        } catch (e: Exception) {
            Log.e("ProfileRepository", "Avatar upload failed", e)
            null
        }
    }

    fun onVisitDateUpdated(date: String) {
        preferencesStore.updateLastVisitDate(date)
    }

    private fun normalizeProfile(profile: Profile): Profile = profile.withDerivedHiveRank()

    private fun buildAvatarBytes(uri: Uri): ByteArray? {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        } ?: return null

        return ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            outputStream.toByteArray()
        }
    }
}
