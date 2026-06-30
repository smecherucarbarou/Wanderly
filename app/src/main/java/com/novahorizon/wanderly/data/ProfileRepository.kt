package com.novahorizon.wanderly.data

import android.content.Context
import android.net.Uri
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.api.decodeRpc
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.util.DateUtils
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.Date

sealed class ProfileError {
    object SchemaCache : ProfileError()
    object MissingProfile : ProfileError()
    object Unauthenticated : ProfileError()
    object UsernameTaken : ProfileError()
    object InvalidUsername : ProfileError()
    object Unknown : ProfileError()
}

sealed class ProfileUpdateResult {
    data class Success(val profile: Profile) : ProfileUpdateResult()
    data class Error(val error: ProfileError, val message: String? = null) : ProfileUpdateResult()
}

class ProfileRepository(
    private val context: Context,
    private val preferencesStore: PreferencesStore,
    private val profileState: ProfileStateHolder = ProfileStateHolder()
) {
    internal data class ClientProfileUpdate(
        val username: String?,
        val avatar_url: String?,
    ) {
        fun isEmpty(): Boolean = username == null && avatar_url == null
    }

    internal data class AdminProfileStatsUpdate(
        val honey: Int,
        val streak_count: Int,
        val hive_rank: Int
    )

    @Serializable
    internal data class MissionLogRpcResponse(
        val success: Boolean,
        val error: String? = null,
        val reward_honey: Int? = null,
        val streak_bonus: Int? = null,
        val streak_count: Int? = null,
        val honey: Int? = null
    )

    @Serializable
    private data class MissionLogParams(
        val p_mission_id: String,
        val p_photo_path: String? = null
    )

    @Serializable
    private data class LocationUpdateParams(
        val lat: Double,
        val lng: Double
    )

    @Serializable
    private data class AdminStatsUpdateParams(
        val target_profile_id: String,
        val new_honey: Int,
        val new_streak_count: Int,
        val new_hive_rank: Int
    )

    @Serializable
    internal data class AdminStatsUpdateResponse(
        val success: Boolean,
        val honey: Int,
        val streak_count: Int,
        val hive_rank: Int
    )

    @Serializable
    private data class UsernameUpdateRpcResponse(
        val success: Boolean,
        val error_code: String? = null,
        val error_message: String? = null
    )

    val currentProfile: StateFlow<Profile?> = profileState.asStateFlow()

    // Shared progress-snapshot writer; exposed so the carved StreakRepository reuses the same instance.
    internal val progressWriter = ProfileProgressWriter(profileState, preferencesStore) { getCurrentProfile() }

    private val avatarRepository = AvatarRepository(context)

    suspend fun getCurrentProfile(): Profile? = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: run {
                logWarn("Auth session not found after waiting.")
                return@withContext null
            }

            val userId = session.user?.id
                ?: return@withContext null
            val loadedProfile = selectProfileWithSchemaRetry(userId)
            if (loadedProfile == null) {
                val error = IllegalStateException("Authenticated user has no profile row.")
                CrashReporter.recordNonFatal(
                    CrashEvent.PROFILE_SYNC_FAILED,
                    error,
                    CrashKey.COMPONENT to "profile_repository",
                    CrashKey.OPERATION to "missing_profile"
                )
                logError("Authenticated profile row missing; signing out.", error)
                signOutForFatalProfileError()
                profileState.value = null
                return@withContext null
            }

            // last_mission_date is no longer selectable from profiles; hydrate it from local
            // state (written by mission-completion RPCs) so streak logic keeps working.
            val cachedMissionDate = preferencesStore.getStoredLastMissionDate()
            var profile = normalizeProfile(loadedProfile).copy(last_mission_date = cachedMissionDate)

            profileState.value = profile
            preferencesStore.cacheProfileStreakState(
                lastMissionDate = profile.last_mission_date,
                streakCount = profile.streak_count
            )
            profile
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
        when (updateProfileDetailed(profile)) {
            is ProfileUpdateResult.Success -> true
            is ProfileUpdateResult.Error -> false
        }
    }

    suspend fun updateProfileDetailed(profile: Profile): ProfileUpdateResult = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
                ?: return@withContext ProfileUpdateResult.Error(ProfileError.Unauthenticated)
            val userId = session.user?.id
                ?: return@withContext ProfileUpdateResult.Error(ProfileError.Unauthenticated)
            val normalizedProfile = normalizeProfile(profile.copy(id = userId))
            persistProfile(normalizedProfile, userId)
            val refreshedProfile = selectProfileWithSchemaRetry(userId) ?: normalizedProfile
            val normalizedRefreshedProfile = normalizeProfile(refreshedProfile)
            profileState.value = normalizedRefreshedProfile
            preferencesStore.cacheProfileStreakState(
                lastMissionDate = normalizedRefreshedProfile.last_mission_date,
                streakCount = normalizedRefreshedProfile.streak_count
            )
            ProfileUpdateResult.Success(normalizedRefreshedProfile)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val profileError = if (isPostgrestSchemaCacheError(e)) ProfileError.SchemaCache else ProfileError.Unknown
            CrashReporter.recordNonFatal(
                CrashEvent.PROFILE_SYNC_FAILED,
                e,
                CrashKey.COMPONENT to "profile_repository",
                CrashKey.OPERATION to "update_profile"
            )
            logError("Update profile failed: ${e.message}", e)
            ProfileUpdateResult.Error(profileError, e.message)
        }
    }

    suspend fun updateUsername(newUsername: String): ProfileUpdateResult = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
                ?: return@withContext ProfileUpdateResult.Error(ProfileError.Unauthenticated)
            val userId = session.user?.id
                ?: return@withContext ProfileUpdateResult.Error(ProfileError.Unauthenticated)

            val response = withPostgrestSchemaCacheRetry {
                SupabaseClient.client.postgrest
                    .rpc("update_profile_username", mapOf("p_username" to newUsername))
                    .decodeRpc<UsernameUpdateRpcResponse>()
            }

            if (!response.success) {
                val error = mapUsernameRpcErrorCode(response.error_code)
                if (error == ProfileError.Unauthenticated) {
                    signOutForFatalProfileError()
                }
                if (error == ProfileError.Unknown) {
                    CrashReporter.recordNonFatal(
                        CrashEvent.PROFILE_SYNC_FAILED,
                        IllegalStateException("Username RPC failed: ${response.error_code} ${response.error_message}"),
                        CrashKey.COMPONENT to "profile_repository",
                        CrashKey.OPERATION to "update_username"
                    )
                }
                return@withContext ProfileUpdateResult.Error(error, response.error_message)
            }

            val refreshed = selectProfileWithSchemaRetry(userId)
            if (refreshed == null) {
                signOutForFatalProfileError()
                profileState.value = null
                return@withContext ProfileUpdateResult.Error(ProfileError.MissingProfile)
            }

            val normalized = normalizeProfile(refreshed)
            profileState.value = normalized
            preferencesStore.cacheProfileStreakState(
                lastMissionDate = normalized.last_mission_date,
                streakCount = normalized.streak_count
            )
            ProfileUpdateResult.Success(normalized)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val profileError = if (isPostgrestSchemaCacheError(e)) ProfileError.SchemaCache else ProfileError.Unknown
            CrashReporter.recordNonFatal(
                CrashEvent.PROFILE_SYNC_FAILED,
                e,
                CrashKey.COMPONENT to "profile_repository",
                CrashKey.OPERATION to "update_username"
            )
            logError("Username update failed: ${e.message}", e)
            ProfileUpdateResult.Error(profileError, e.message)
        }
    }

    suspend fun adminUpdateProfileStats(
        profileId: String,
        honey: Int,
        streakCount: Int
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = toAdminProfileStatsUpdate(honey, streakCount)
            val response = SupabaseClient.client.postgrest
                .rpc(
                    "admin_update_profile_stats",
                    AdminStatsUpdateParams(
                        target_profile_id = profileId,
                        new_honey = payload.honey,
                        new_streak_count = payload.streak_count,
                        new_hive_rank = payload.hive_rank
                    )
                )
                .decodeList<AdminStatsUpdateResponse>().firstOrNull()
                ?: return@withContext false

            if (!response.success) {
                logWarn("Admin stats update did not persist for profile.")
                return@withContext false
            }

            val refreshed = profileState.updateAndGet { current ->
                if (current?.id == profileId) {
                    current.copy(
                        honey = response.honey,
                        streak_count = response.streak_count,
                        hive_rank = response.hive_rank
                    )
                } else {
                    current
                }
            }
            if (refreshed?.id == profileId) {
                preferencesStore.cacheProfileStreakState(
                    lastMissionDate = refreshed.last_mission_date,
                    streakCount = refreshed.streak_count
                )
            }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            CrashReporter.recordNonFatal(
                CrashEvent.PROFILE_SYNC_FAILED,
                e,
                CrashKey.COMPONENT to "profile_repository",
                CrashKey.OPERATION to "admin_update_profile_stats"
            )
            logError("Admin stats update failed: ${e.message}", e)
            false
        }
    }

    suspend fun logMissionCompletion(
        missionId: String,
        photoPath: String? = null
    ): MissionCompletionResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext MissionCompletionResult.Unauthenticated
        session.user?.id ?: return@withContext MissionCompletionResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("log_mission_completion", MissionLogParams(p_mission_id = missionId, p_photo_path = photoPath))
                .decodeRpc<MissionLogRpcResponse>()

            if (response.success) {
                // The new RPC does not echo a mission date; completing now means today (UTC).
                val today = DateUtils.formatUtcDate(Date())
                val updated = progressWriter.apply(
                    honey = response.honey,
                    streakCount = response.streak_count,
                    lastMissionDate = today
                )
                MissionCompletionResult.Completed(
                    // Prefer the post-snapshot balance (falls back to the prior profile value when the RPC
                    // omits a field) rather than collapsing a missing value to 0.
                    honey = updated?.honey ?: response.honey ?: 0,
                    streakCount = updated?.streak_count ?: response.streak_count ?: 0,
                    lastMissionDate = today,
                    rewardHoney = response.reward_honey ?: 0,
                    streakBonusHoney = response.streak_bonus ?: 0
                )
            } else {
                mapMissionLogError(response.error, profileState.value)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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

                val updated = profileState.updateAndGet { it?.copy(last_lat = lat, last_lng = lng) }
                SensitiveProfileMutationResult.Success(updated)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mapSensitiveProfileMutationFailure(e)
            }
        }

    suspend fun uploadAvatar(uri: Uri, profileId: String): AvatarUploadResult {
        val result = avatarRepository.uploadAvatar(uri, profileId)
        if (result is AvatarUploadResult.Success) {
            // Avatar upload now returns the public URL so profile state refreshes with the cache-busted image.
            profileState.update { current ->
                if (current?.id == profileId) current.copy(avatar_url = result.avatarUrl) else current
            }
        }
        return result
    }

    suspend fun onVisitDateUpdated(date: String) {
        preferencesStore.updateLastVisitDate(date)
    }

    private fun normalizeProfile(profile: Profile): Profile {
        return profile.copy(
            avatar_url = normalizeAvatarUrl(profile.avatar_url)
        ).withDerivedHiveRank()
    }

    private suspend fun persistProfile(profile: Profile, userId: String = profile.id) {
        val payload = toClientProfileUpdate(profile)
        if (payload.isEmpty()) return

        // Only user-editable profile columns are patched directly; server-owned fields use RPCs.
        withPostgrestSchemaCacheRetry {
            SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].update({
                payload.username?.let { Profile::username setTo it }
                payload.avatar_url?.let { Profile::avatar_url setTo it }
            }) {
                filter { eq("id", userId) }
            }
        }
    }

    private suspend fun selectProfileWithSchemaRetry(userId: String): Profile? =
        withPostgrestSchemaCacheRetry {
            // Only request columns the client is still allowed to read; hidden server-owned
            // columns (last_*, updated_at, admin_role) are no longer selectable directly.
            SupabaseClient.client.postgrest[Constants.TABLE_PROFILES]
                .select(Columns.list(*PROFILE_VISIBLE_COLUMNS.toTypedArray())) { filter { eq("id", userId) } }
                .decodeSingleOrNull<Profile>()
        }

    private suspend fun signOutForFatalProfileError() {
        try {
            SupabaseClient.client.auth.signOut()
            preferencesStore.clearAll()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            CrashReporter.recordNonFatal(
                CrashEvent.AUTH_SIGN_OUT_FAILED,
                e,
                CrashKey.COMPONENT to "profile_repository",
                CrashKey.OPERATION to "fatal_profile_sign_out"
            )
            logError("Fatal profile sign-out failed: ${e.message}", e)
        }
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

    companion object {
        internal fun normalizeAvatarUrl(avatarUrl: String?): String? =
            AvatarRepository.normalizeAvatarUrl(avatarUrl)

        internal fun toClientProfileUpdate(profile: Profile): ClientProfileUpdate {
            return ClientProfileUpdate(
                username = profile.username,
                avatar_url = normalizeAvatarUrl(profile.avatar_url),
            )
        }

        /** Pure mapping of a log_mission_completion error code. [snapshot] supplies the current
         *  balance for the `already_completed` echo. Extracted from the instance method for testing. */
        internal fun mapMissionLogError(error: String?, snapshot: Profile?): MissionCompletionResult =
            when (error) {
                "already_completed" -> MissionCompletionResult.AlreadyCompleted(
                    honey = snapshot?.honey ?: 0,
                    streakCount = snapshot?.streak_count ?: 0,
                    lastMissionDate = snapshot?.last_mission_date
                )
                "not_your_mission" -> MissionCompletionResult.Forbidden
                "mission_not_found" -> MissionCompletionResult.MissionNotFound
                "not_authenticated" -> MissionCompletionResult.Unauthenticated
                else -> MissionCompletionResult.ServerFailure
            }

        internal fun mapUsernameRpcErrorCode(errorCode: String?): ProfileError {
            return when (errorCode) {
                "username_taken" -> ProfileError.UsernameTaken
                "invalid_username" -> ProfileError.InvalidUsername
                "not_authenticated" -> ProfileError.Unauthenticated
                else -> ProfileError.Unknown
            }
        }

        internal fun toAdminProfileStatsUpdate(honey: Int, streakCount: Int): AdminProfileStatsUpdate {
            val safeHoney = honey.coerceIn(0, MAX_ADMIN_HONEY)
            val safeStreakCount = streakCount.coerceIn(0, MAX_ADMIN_STREAK_COUNT)
            return AdminProfileStatsUpdate(
                honey = safeHoney,
                streak_count = safeStreakCount,
                hive_rank = HiveRank.fromHoney(safeHoney)
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

        private const val MAX_ADMIN_HONEY = 1_000_000
        private const val MAX_ADMIN_STREAK_COUNT = 3_650
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
