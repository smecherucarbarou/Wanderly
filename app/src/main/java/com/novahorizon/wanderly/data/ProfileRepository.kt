package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.observability.AppLogger

import android.content.Context
import android.net.Uri
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

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

    private val avatarRepository = AvatarRepository(context)

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
            preferencesStore.cacheProfileStreakState(
                lastMissionDate = profile.last_mission_date,
                streakCount = profile.streak_count
            )
            profile
        } catch (e: Exception) {
            CrashReporter.recordNonFatal(
                CrashEvent.PROFILE_SYNC_FAILED,
                e,
                CrashKey.COMPONENT to "profile_repository",
                CrashKey.OPERATION to "get_current_profile"
            )
            logError("Error getting profile: ${e.message}", e)
            null
        }
    }

    suspend fun updateProfile(profile: Profile): Boolean = withContext(Dispatchers.IO) {
        try {
            val normalizedProfile = normalizeProfile(profile)
            persistProfile(normalizedProfile)
            _currentProfile.value = normalizedProfile
            preferencesStore.cacheProfileStreakState(
                lastMissionDate = normalizedProfile.last_mission_date,
                streakCount = normalizedProfile.streak_count
            )
            true
        } catch (e: Exception) {
            CrashReporter.recordNonFatal(
                CrashEvent.PROFILE_SYNC_FAILED,
                e,
                CrashKey.COMPONENT to "profile_repository",
                CrashKey.OPERATION to "update_profile"
            )
            logError("Update profile failed: ${e.message}", e)
            false
        }
    }

    suspend fun resetMissionDateForTesting(): Boolean = withContext(Dispatchers.IO) {
        try {
            val profile = getCurrentProfile() ?: return@withContext false
            updateProfile(profile.copy(last_mission_date = "2000-01-01"))
        } catch (e: Exception) {
            CrashReporter.recordNonFatal(
                CrashEvent.PROFILE_SYNC_FAILED,
                e,
                CrashKey.COMPONENT to "profile_repository",
                CrashKey.OPERATION to "reset_mission_date"
            )
            logError("Operation failed", e)
            false
        }
    }

    suspend fun uploadAvatar(uri: Uri, profileId: String): String = avatarRepository.uploadAvatar(uri, profileId)

    suspend fun onVisitDateUpdated(date: String) {
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

    companion object {
        internal fun normalizeAvatarUrl(avatarUrl: String?): String? =
            AvatarRepository.normalizeAvatarUrl(avatarUrl)

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
        ): AvatarRepository.AvatarStorageTarget =
            AvatarRepository.buildAvatarStorageTarget(baseUrl, bucket, profileId, versionToken)

        internal fun buildAvatarUploadFailureMessage(code: Int, responseBody: String): String =
            AvatarRepository.buildAvatarUploadFailureMessage(code, responseBody)

        internal fun extractLocalFilePath(scheme: String?, path: String?): String? =
            AvatarRepository.extractLocalFilePath(scheme, path)

        internal fun isAvatarFileUsable(exists: Boolean, length: Long): Boolean =
            AvatarRepository.isAvatarFileUsable(exists, length)
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d("ProfileRepository", LogRedactor.redact(message))
        }
    }

    private fun logWarn(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.w("ProfileRepository", LogRedactor.redact(message))
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val safeMessage = LogRedactor.redact(message)
            if (throwable != null) {
                AppLogger.e(
                    "ProfileRepository",
                    "$safeMessage [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
                )
            } else {
                AppLogger.e("ProfileRepository", safeMessage)
            }
        }
    }
}
