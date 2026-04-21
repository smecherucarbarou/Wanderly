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
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class ProfileRepository(
    private val context: Context,
    private val preferencesStore: PreferencesStore
) {
    internal data class AvatarStorageTarget(
        val filePath: String,
        val uploadUrl: String,
        val publicUrl: String,
        val useUpsert: Boolean
    )

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
                logWarn("Auth session not found after waiting.")
                return@withContext null
            }

            val userId = session.user?.id
                ?: return@withContext null
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
            logError("Error getting profile: ${e.message}", e)
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
            logError("Update profile failed: ${e.message}", e)
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

    suspend fun uploadAvatar(uri: Uri, profileId: String): String = withContext(Dispatchers.IO) {
        val auth = SupabaseClient.client.auth
        val accessToken = auth.currentAccessTokenOrNull() ?: run {
            logError("No access token available for avatar upload")
            throw IllegalStateException("No access token available for avatar upload")
        }

        logDebug("Uploading avatar for profile: $profileId")

        try {
            val avatarBytes = buildAvatarBytes(uri) ?: run {
                logError("Could not read image bytes from: $uri")
                throw IllegalStateException("Could not read image bytes from: $uri")
            }

            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
            val bucket = Constants.STORAGE_BUCKET_AVATARS
            val target = buildAvatarStorageTarget(
                baseUrl = baseUrl,
                bucket = bucket,
                profileId = profileId,
                versionToken = System.currentTimeMillis().toString()
            )

            logDebug("Target upload URL: ${target.uploadUrl}")

            val request = Request.Builder()
                .url(target.uploadUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .apply { if (target.useUpsert) addHeader("x-upsert", "true") }
                .post(avatarBytes.toRequestBody("image/jpeg".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = buildAvatarUploadFailureMessage(response.code, responseBody)
                    logError(message)
                    throw IllegalStateException(message)
                }
                logDebug("Avatar upload successful for $profileId")
            }

            logDebug("Generated avatar path: ${target.filePath}")
            target.filePath
        } catch (e: Exception) {
            logError("Exception during avatar upload", e)
            throw e
        }
    }

    fun onVisitDateUpdated(date: String) {
        preferencesStore.updateLastVisitDate(date)
    }

    private fun normalizeProfile(profile: Profile): Profile {
        return profile.copy(
            avatar_url = normalizeAvatarUrl(profile.avatar_url)
        ).withDerivedHiveRank()
    }

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
        val localFilePath = extractLocalFilePath(uri.scheme, uri.path)
        if (localFilePath != null) {
            val avatarFile = File(localFilePath)
            if (!isAvatarFileUsable(avatarFile.exists(), avatarFile.length())) {
                return null
            }
            return avatarFile.readBytes()
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        openAvatarInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, bounds)
        } ?: return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqWidth = 512, reqHeight = 512)
        }
        val bitmap = openAvatarInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        } ?: return null

        return ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            outputStream.toByteArray()
        }
    }

    private fun openAvatarInputStream(uri: Uri) = extractLocalFilePath(uri.scheme, uri.path)?.let { FileInputStream(it) }
        ?: context.contentResolver.openInputStream(uri)

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
        private const val UPLOAD_AVATAR_URL_MARKER = "/storage/v1/object/avatars/"
        private const val PUBLIC_AVATAR_URL_MARKER = "/storage/v1/object/public/avatars/"
        private const val AUTHENTICATED_AVATAR_URL_MARKER = "/storage/v1/object/authenticated/avatars/"

        internal fun normalizeAvatarUrl(avatarUrl: String?): String? {
            val trimmed = avatarUrl?.trim().orEmpty()
            if (trimmed.isEmpty()) return null

            return when {
                trimmed.startsWith("profiles/") -> trimmed.substringBefore('?')
                trimmed.contains(UPLOAD_AVATAR_URL_MARKER) -> trimmed.substringAfter(UPLOAD_AVATAR_URL_MARKER).substringBefore('?')
                trimmed.contains(PUBLIC_AVATAR_URL_MARKER) -> trimmed.substringAfter(PUBLIC_AVATAR_URL_MARKER).substringBefore('?')
                trimmed.contains(AUTHENTICATED_AVATAR_URL_MARKER) -> trimmed.substringAfter(AUTHENTICATED_AVATAR_URL_MARKER).substringBefore('?')
                else -> trimmed
            }
        }

        internal fun toClientProfileUpdate(profile: Profile): ClientProfileUpdate {
            return ClientProfileUpdate(
                username = profile.username,
                honey = profile.honey,
                hive_rank = profile.hive_rank,
                badges = profile.badges,
                cities_visited = profile.cities_visited,
                avatar_url = normalizeAvatarUrl(profile.avatar_url),
                last_mission_date = profile.last_mission_date,
                last_lat = profile.last_lat,
                last_lng = profile.last_lng,
                friend_code = profile.friend_code,
                streak_count = profile.streak_count,
                explorer_class = profile.explorer_class
            )
        }

        internal fun buildAvatarStorageTarget(
            baseUrl: String,
            bucket: String,
            profileId: String,
            versionToken: String
        ): AvatarStorageTarget {
            val normalizedBaseUrl = baseUrl.trimEnd('/')
            val filePath = "profiles/$profileId/avatar.jpg"
            return AvatarStorageTarget(
                filePath = filePath,
                uploadUrl = "$normalizedBaseUrl/storage/v1/object/$bucket/$filePath",
                publicUrl = "$normalizedBaseUrl/storage/v1/object/public/$bucket/$filePath?v=$versionToken",
                useUpsert = true
            )
        }

        internal fun buildAvatarUploadFailureMessage(code: Int, responseBody: String): String {
            val trimmedResponse = responseBody.trim()
            return if (trimmedResponse.isEmpty()) {
                "Avatar upload failed with code $code"
            } else {
                "Avatar upload failed with code $code: $trimmedResponse"
            }
        }

        internal fun extractLocalFilePath(scheme: String?, path: String?): String? {
            return path?.takeIf { scheme.equals("file", ignoreCase = true) && it.isNotBlank() }
        }

        internal fun isAvatarFileUsable(exists: Boolean, length: Long): Boolean {
            return exists && length > 0L
        }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("ProfileRepository", message)
        }
    }

    private fun logWarn(message: String) {
        if (BuildConfig.DEBUG) {
            Log.w("ProfileRepository", message)
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.e("ProfileRepository", message, throwable)
            } else {
                Log.e("ProfileRepository", message)
            }
        }
    }
}
