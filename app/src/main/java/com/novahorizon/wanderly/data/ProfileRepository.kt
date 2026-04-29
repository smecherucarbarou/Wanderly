package com.novahorizon.wanderly.data

import android.content.Context
import android.net.Uri
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.UUID

class ProfileRepository(
    private val context: Context,
    private val preferencesStore: PreferencesStore
) {
    internal data class ClientProfileUpdate(
        val username: String?,
        val badges: List<String>?,
        val cities_visited: List<String>?,
        val avatar_url: String?,
        val friend_code: String?,
        val explorer_class: String?
    )

    @Serializable
    internal data class ClientProfileInsert(
        val id: String,
        val username: String?,
        val friend_code: String?
    )

    @Serializable
    private data class MissionCompletionRpcResponse(
        val completed: Boolean,
        val duplicate: Boolean,
        val honey: Int,
        val streak_count: Int,
        val last_mission_date: String? = null,
        val reward_honey: Int,
        val streak_bonus_honey: Int
    )

    @Serializable
    private data class LocationUpdateParams(
        val lat: Double,
        val lng: Double
    )

    @Serializable
    private data class RestoreStreakParams(
        val cost: Int
    )

    @Serializable
    private data class StreakMutationRpcResponse(
        val updated: Boolean? = null,
        val restored: Boolean? = null,
        val reason: String? = null,
        val honey: Int? = null,
        val streak_count: Int? = null,
        val last_mission_date: String? = null
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
                SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].upsert(
                    ClientProfileInsert(
                        id = newProfile.id,
                        username = newProfile.username,
                        friend_code = newProfile.friend_code
                    )
                )
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

    suspend fun completeMission(): MissionCompletionResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext MissionCompletionResult.Unauthenticated
        session.user?.id ?: return@withContext MissionCompletionResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("complete_mission")
                .decodeSingle<MissionCompletionRpcResponse>()

            if (response.duplicate) {
                applyProgressSnapshot(
                    honey = response.honey,
                    streakCount = response.streak_count,
                    lastMissionDate = response.last_mission_date
                )
                MissionCompletionResult.AlreadyCompleted(
                    honey = response.honey,
                    streakCount = response.streak_count,
                    lastMissionDate = response.last_mission_date
                )
            } else if (response.completed && response.last_mission_date != null) {
                applyProgressSnapshot(
                    honey = response.honey,
                    streakCount = response.streak_count,
                    lastMissionDate = response.last_mission_date
                )
                MissionCompletionResult.Completed(
                    honey = response.honey,
                    streakCount = response.streak_count,
                    lastMissionDate = response.last_mission_date,
                    rewardHoney = response.reward_honey,
                    streakBonusHoney = response.streak_bonus_honey
                )
            } else {
                MissionCompletionResult.ServerFailure
            }
        } catch (e: Exception) {
            mapMissionCompletionFailure(e)
        }
    }

    suspend fun updateProfileLocation(lat: Double, lng: Double): SensitiveProfileMutationResult =
        withContext(Dispatchers.IO) {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
                ?: return@withContext SensitiveProfileMutationResult.Unauthenticated
            session.user?.id ?: return@withContext SensitiveProfileMutationResult.Unauthenticated

            try {
                SupabaseClient.client.postgrest
                    .rpc("update_profile_location", LocationUpdateParams(lat, lng))

                _currentProfile.value = _currentProfile.value?.copy(last_lat = lat, last_lng = lng)
                SensitiveProfileMutationResult.Success(_currentProfile.value)
            } catch (e: Exception) {
                mapSensitiveProfileMutationFailure(e)
            }
        }

    suspend fun acceptStreakLoss(): SensitiveProfileMutationResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext SensitiveProfileMutationResult.Unauthenticated
        session.user?.id ?: return@withContext SensitiveProfileMutationResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("accept_streak_loss")
                .decodeSingle<StreakMutationRpcResponse>()
            if (response.updated == true) {
                val profile = applyProgressSnapshot(
                    honey = response.honey,
                    streakCount = response.streak_count,
                    lastMissionDate = response.last_mission_date
                )
                SensitiveProfileMutationResult.Success(profile)
            } else {
                SensitiveProfileMutationResult.Rejected("not_hard_lost")
            }
        } catch (e: Exception) {
            mapSensitiveProfileMutationFailure(e)
        }
    }

    suspend fun restoreStreak(cost: Int): SensitiveProfileMutationResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext SensitiveProfileMutationResult.Unauthenticated
        session.user?.id ?: return@withContext SensitiveProfileMutationResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("restore_streak", RestoreStreakParams(cost))
                .decodeSingle<StreakMutationRpcResponse>()
            if (response.restored == true) {
                val profile = applyProgressSnapshot(
                    honey = response.honey,
                    streakCount = response.streak_count,
                    lastMissionDate = response.last_mission_date
                )
                SensitiveProfileMutationResult.Success(profile, response.reason)
            } else {
                SensitiveProfileMutationResult.Rejected(response.reason ?: "not_restored")
            }
        } catch (e: Exception) {
            mapSensitiveProfileMutationFailure(e)
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

        // Only user-editable profile columns are patched directly; server-owned fields use RPCs.
        SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].update({
            Profile::username setTo payload.username
            Profile::badges setTo payload.badges
            Profile::cities_visited setTo payload.cities_visited
            Profile::avatar_url setTo payload.avatar_url
            Profile::friend_code setTo payload.friend_code
            Profile::explorer_class setTo payload.explorer_class
        }) {
            filter { eq("id", profile.id) }
        }
    }

    private suspend fun applyProgressSnapshot(
        honey: Int?,
        streakCount: Int?,
        lastMissionDate: String?
    ): Profile? {
        val base = _currentProfile.value ?: getCurrentProfile()
        val updated = base?.copy(
            honey = honey ?: base.honey,
            streak_count = streakCount ?: base.streak_count,
            last_mission_date = lastMissionDate ?: base.last_mission_date,
            hive_rank = HiveRank.fromHoney(honey ?: base.honey)
        )
        if (updated != null) {
            _currentProfile.value = updated
            preferencesStore.cacheProfileStreakState(
                lastMissionDate = updated.last_mission_date,
                streakCount = updated.streak_count
            )
        }
        return updated
    }

    private fun mapMissionCompletionFailure(error: Exception): MissionCompletionResult =
        when (error) {
            is SerializationException -> MissionCompletionResult.ParseFailure
            is HttpRequestTimeoutException,
            is IOException -> MissionCompletionResult.NetworkFailure
            is RestException -> when (error.statusCode) {
                401 -> MissionCompletionResult.Unauthenticated
                403 -> MissionCompletionResult.Forbidden
                429 -> MissionCompletionResult.RateLimited
                in 500..599 -> MissionCompletionResult.ServerFailure
                else -> MissionCompletionResult.ServerFailure
            }
            else -> MissionCompletionResult.ServerFailure
        }

    private fun mapSensitiveProfileMutationFailure(error: Exception): SensitiveProfileMutationResult =
        when (error) {
            is SerializationException -> SensitiveProfileMutationResult.ParseFailure
            is HttpRequestTimeoutException,
            is IOException -> SensitiveProfileMutationResult.NetworkFailure
            is RestException -> when (error.statusCode) {
                401 -> SensitiveProfileMutationResult.Unauthenticated
                403 -> SensitiveProfileMutationResult.Forbidden
                429 -> SensitiveProfileMutationResult.RateLimited
                in 500..599 -> SensitiveProfileMutationResult.ServerFailure
                else -> SensitiveProfileMutationResult.ServerFailure
            }
            else -> SensitiveProfileMutationResult.ServerFailure
        }

    companion object {
        internal fun normalizeAvatarUrl(avatarUrl: String?): String? =
            AvatarRepository.normalizeAvatarUrl(avatarUrl)

        internal fun toClientProfileUpdate(profile: Profile): ClientProfileUpdate {
            return ClientProfileUpdate(
                username = profile.username,
                badges = profile.badges,
                cities_visited = profile.cities_visited,
                avatar_url = normalizeAvatarUrl(profile.avatar_url),
                friend_code = profile.friend_code,
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
