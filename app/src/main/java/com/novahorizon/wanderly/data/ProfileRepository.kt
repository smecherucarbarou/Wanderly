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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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
    private val preferencesStore: PreferencesStore
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
    private data class RestoreStreakParams(
        val cost: Int
    )

    @Serializable
    internal data class StreakMutationRpcResponse(
        val updated: Boolean? = null,
        val restored: Boolean? = null,
        val reason: String? = null,
        val honey: Int? = null,
        val streak_count: Int? = null,
        val last_mission_date: String? = null
    )

    @Serializable
    internal data class StreakFreezeRpcResponse(
        val success: Boolean,
        val error: String? = null,
        val freezes_left: Int? = null
    )

    @Serializable
    private data class ClaimMilestoneParams(
        val p_threshold: Int
    )

    @Serializable
    internal data class StreakMilestoneClaimRpcResponse(
        val success: Boolean,
        val error: String? = null,
        val reward_honey: Int? = null,
        val badge: String? = null
    )

    @Serializable
    private data class StreakMilestoneClaimRow(
        val threshold: Int
    )

    @Serializable
    private data class ReferralClaimParams(
        val p_friend_code: String
    )

    @Serializable
    internal data class ReferralClaimRpcResponse(
        val success: Boolean,
        val error: String? = null,
        val reward_honey: Int? = null
    )

    @Serializable
    private data class ReferralRow(
        val referred_id: String
    )

    @Serializable
    private data class ShopItemParams(
        val p_item_id: String
    )

    @Serializable
    internal data class PurchaseShopItemRpcResponse(
        val success: Boolean,
        val error: String? = null,
        val honey: Int? = null,
        val item: String? = null
    )

    @Serializable
    internal data class EquipCosmeticRpcResponse(
        val success: Boolean,
        val error: String? = null,
        val type: String? = null
    )

    @Serializable
    private data class UsernameUpdateRpcResponse(
        val success: Boolean,
        val error_code: String? = null,
        val error_message: String? = null
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
                _currentProfile.value = null
                return@withContext null
            }

            // last_mission_date is no longer selectable from profiles; hydrate it from local
            // state (written by mission-completion RPCs) so streak logic keeps working.
            val cachedMissionDate = preferencesStore.getStoredLastMissionDate()
            var profile = normalizeProfile(loadedProfile).copy(last_mission_date = cachedMissionDate)

            _currentProfile.value = profile
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
            _currentProfile.value = normalizedRefreshedProfile
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
                _currentProfile.value = null
                return@withContext ProfileUpdateResult.Error(ProfileError.MissingProfile)
            }

            val normalized = normalizeProfile(refreshed)
            _currentProfile.value = normalized
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

            val refreshed = _currentProfile.updateAndGet { current ->
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
                val updated = applyProgressSnapshot(
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
                mapMissionLogError(response.error)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mapMissionCompletionFailure(e)
        }
    }

    private fun mapMissionLogError(error: String?): MissionCompletionResult =
        when (error) {
            "already_completed" -> {
                val snapshot = _currentProfile.value
                MissionCompletionResult.AlreadyCompleted(
                    honey = snapshot?.honey ?: 0,
                    streakCount = snapshot?.streak_count ?: 0,
                    lastMissionDate = snapshot?.last_mission_date
                )
            }
            "not_your_mission" -> MissionCompletionResult.Forbidden
            "mission_not_found" -> MissionCompletionResult.MissionNotFound
            "not_authenticated" -> MissionCompletionResult.Unauthenticated
            else -> MissionCompletionResult.ServerFailure
        }

    suspend fun updateProfileLocation(lat: Double, lng: Double): SensitiveProfileMutationResult =
        withContext(Dispatchers.IO) {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
                ?: return@withContext SensitiveProfileMutationResult.Unauthenticated
            session.user?.id ?: return@withContext SensitiveProfileMutationResult.Unauthenticated

            try {
                SupabaseClient.client.postgrest
                    .rpc("update_profile_location", LocationUpdateParams(lat, lng))

                val updated = _currentProfile.updateAndGet { it?.copy(last_lat = lat, last_lng = lng) }
                SensitiveProfileMutationResult.Success(updated)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
                .decodeList<StreakMutationRpcResponse>().firstOrNull()
                ?: return@withContext SensitiveProfileMutationResult.ParseFailure
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
            if (e is CancellationException) throw e
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
                .decodeList<StreakMutationRpcResponse>().firstOrNull()
                ?: return@withContext SensitiveProfileMutationResult.ParseFailure
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
            if (e is CancellationException) throw e
            mapSensitiveProfileMutationFailure(e)
        }
    }

    suspend fun useStreakFreeze(): SensitiveProfileMutationResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext SensitiveProfileMutationResult.Unauthenticated
        session.user?.id ?: return@withContext SensitiveProfileMutationResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("use_streak_freeze")
                .decodeRpc<StreakFreezeRpcResponse>()
            if (response.success) {
                val freezesLeft = response.freezes_left ?: 0
                val updated = _currentProfile.updateAndGet { it?.copy(streak_freezes = freezesLeft) }
                SensitiveProfileMutationResult.Success(updated)
            } else {
                SensitiveProfileMutationResult.Rejected(response.error ?: "unknown")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mapSensitiveProfileMutationFailure(e)
        }
    }

    suspend fun getStreakMilestones(): List<StreakMilestoneStatus> = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext emptyList()
        val userId = session.user?.id ?: return@withContext emptyList()

        try {
            val catalog = withPostgrestSchemaCacheRetry {
                SupabaseClient.client.postgrest[Constants.TABLE_STREAK_MILESTONES]
                    .select()
                    .decodeList<StreakMilestone>()
            }.sortedBy { it.threshold }

            val claimedThresholds = withPostgrestSchemaCacheRetry {
                SupabaseClient.client.postgrest[Constants.TABLE_STREAK_MILESTONE_CLAIMS]
                    .select(Columns.list("threshold")) { filter { eq("user_id", userId) } }
                    .decodeList<StreakMilestoneClaimRow>()
            }.map { it.threshold }.toSet()

            val streak = _currentProfile.value?.streak_count ?: 0
            catalog.map { milestone ->
                StreakMilestoneStatus(
                    threshold = milestone.threshold,
                    rewardHoney = milestone.reward_honey,
                    badge = milestone.badge,
                    reached = streak >= milestone.threshold,
                    claimed = milestone.threshold in claimedThresholds
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Failed to load streak milestones: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun claimStreakMilestone(threshold: Int): SensitiveProfileMutationResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext SensitiveProfileMutationResult.Unauthenticated
        session.user?.id ?: return@withContext SensitiveProfileMutationResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("claim_streak_milestone", ClaimMilestoneParams(threshold))
                .decodeRpc<StreakMilestoneClaimRpcResponse>()
            if (response.success) {
                // Live RPC grants honey + badge server-side and does not echo the new
                // balance, so re-fetch the profile to reflect honey and badges.
                val profile = getCurrentProfile()
                SensitiveProfileMutationResult.Success(profile)
            } else {
                SensitiveProfileMutationResult.Rejected(response.error ?: "unknown")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mapSensitiveProfileMutationFailure(e)
        }
    }

    suspend fun hasClaimedReferral(): Boolean = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext false
        val userId = session.user?.id ?: return@withContext false
        try {
            withPostgrestSchemaCacheRetry {
                SupabaseClient.client.postgrest[Constants.TABLE_REFERRALS]
                    .select(Columns.list("referred_id")) { filter { eq("referred_id", userId) } }
                    .decodeList<ReferralRow>()
            }.isNotEmpty()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Failed to load referral state: ${e.message}", e)
            false
        }
    }

    suspend fun claimReferral(friendCode: String): SensitiveProfileMutationResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext SensitiveProfileMutationResult.Unauthenticated
        session.user?.id ?: return@withContext SensitiveProfileMutationResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("claim_referral", ReferralClaimParams(friendCode))
                .decodeRpc<ReferralClaimRpcResponse>()
            if (response.success) {
                // RPC credits honey to both parties server-side without echoing the new
                // balance, so re-fetch the profile. Reward amount is carried in `reason`.
                val profile = getCurrentProfile()
                SensitiveProfileMutationResult.Success(profile, response.reward_honey?.toString())
            } else {
                SensitiveProfileMutationResult.Rejected(response.error ?: "unknown")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mapSensitiveProfileMutationFailure(e)
        }
    }

    suspend fun getShopItems(): List<ShopItemStatus> = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext emptyList()
        val userId = session.user?.id ?: return@withContext emptyList()

        try {
            val catalog = withPostgrestSchemaCacheRetry {
                SupabaseClient.client.postgrest[Constants.TABLE_SHOP_ITEMS]
                    .select { filter { eq("active", true) } }
                    .decodeList<ShopItem>()
            }.sortedWith(compareBy({ it.type }, { it.cost_honey }))

            val ownedIds = withPostgrestSchemaCacheRetry {
                SupabaseClient.client.postgrest[Constants.TABLE_USER_INVENTORY]
                    .select(Columns.list("item_id")) { filter { eq("user_id", userId) } }
                    .decodeList<UserInventoryItemRow>()
            }.map { it.item_id }.toSet()

            val profile = _currentProfile.value
            val honey = profile?.honey ?: 0
            val equippedIds = setOfNotNull(
                profile?.equipped_frame,
                profile?.equipped_skin,
                profile?.equipped_widget_theme
            )

            catalog.map { item ->
                ShopItemStatus(
                    id = item.id,
                    sku = item.sku,
                    name = item.name,
                    type = item.type,
                    costHoney = item.cost_honey,
                    owned = item.id in ownedIds,
                    equipped = item.id in equippedIds,
                    affordable = honey >= item.cost_honey
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Failed to load shop items: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun purchaseShopItem(itemId: String): ShopPurchaseResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext ShopPurchaseResult.Unauthenticated
        session.user?.id ?: return@withContext ShopPurchaseResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("purchase_shop_item", ShopItemParams(itemId))
                .decodeRpc<PurchaseShopItemRpcResponse>()
            val result = mapPurchaseResponse(response)
            if (result is ShopPurchaseResult.Success) {
                // RPC echoes the post-debit balance; refresh honey in the shared profile state.
                _currentProfile.update { it?.copy(honey = result.newHoney) }
            }
            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Shop purchase failed: ${e.message}", e)
            ShopPurchaseResult.Failure
        }
    }

    suspend fun equipCosmetic(itemId: String): ShopEquipResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext ShopEquipResult.Unauthenticated
        session.user?.id ?: return@withContext ShopEquipResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("equip_cosmetic", ShopItemParams(itemId))
                .decodeRpc<EquipCosmeticRpcResponse>()
            val result = mapEquipResponse(response)
            if (result is ShopEquipResult.Success) {
                applyEquippedCosmetic(result.type, itemId)
            }
            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Shop equip failed: ${e.message}", e)
            ShopEquipResult.Failure
        }
    }

    private fun applyEquippedCosmetic(type: String?, itemId: String) {
        _currentProfile.update { current ->
            when (type) {
                CosmeticType.AVATAR_FRAME -> current?.copy(equipped_frame = itemId)
                CosmeticType.BUZZY_SKIN -> current?.copy(equipped_skin = itemId)
                CosmeticType.WIDGET_THEME -> current?.copy(equipped_widget_theme = itemId)
                else -> current
            }
        }
    }

    suspend fun uploadAvatar(uri: Uri, profileId: String): AvatarUploadResult {
        val result = avatarRepository.uploadAvatar(uri, profileId)
        if (result is AvatarUploadResult.Success) {
            // Avatar upload now returns the public URL so profile state refreshes with the cache-busted image.
            _currentProfile.update { current ->
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

    private suspend fun <T> withPostgrestSchemaCacheRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!isPostgrestSchemaCacheError(e)) {
                throw e
            }
            delay(POSTGREST_SCHEMA_CACHE_RETRY_DELAY_MS)
            block()
        }
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

    private suspend fun applyProgressSnapshot(
        honey: Int?,
        streakCount: Int?,
        lastMissionDate: String?
    ): Profile? {
        // Ensure a base profile is loaded; the suspend fetch can't run inside the atomic update block.
        if (_currentProfile.value == null) {
            getCurrentProfile()
        }
        val updated = _currentProfile.updateAndGet { current ->
            current?.copy(
                honey = honey ?: current.honey,
                streak_count = streakCount ?: current.streak_count,
                last_mission_date = lastMissionDate ?: current.last_mission_date,
                hive_rank = HiveRank.fromHoney(honey ?: current.honey)
            )
        }
        if (updated != null) {
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
                avatar_url = normalizeAvatarUrl(profile.avatar_url),
            )
        }

        internal fun mapPurchaseResponse(response: PurchaseShopItemRpcResponse): ShopPurchaseResult =
            if (response.success) {
                ShopPurchaseResult.Success(newHoney = response.honey ?: 0, sku = response.item)
            } else when (response.error) {
                "item_unavailable" -> ShopPurchaseResult.ItemUnavailable
                "already_owned" -> ShopPurchaseResult.AlreadyOwned
                "insufficient_honey" -> ShopPurchaseResult.InsufficientHoney
                "not_authenticated" -> ShopPurchaseResult.Unauthenticated
                else -> ShopPurchaseResult.Failure
            }

        internal fun mapEquipResponse(response: EquipCosmeticRpcResponse): ShopEquipResult =
            if (response.success) {
                ShopEquipResult.Success(type = response.type)
            } else when (response.error) {
                "not_owned" -> ShopEquipResult.NotOwned
                "not_authenticated" -> ShopEquipResult.Unauthenticated
                else -> ShopEquipResult.Failure
            }

        internal fun mapUsernameRpcErrorCode(errorCode: String?): ProfileError {
            return when (errorCode) {
                "username_taken" -> ProfileError.UsernameTaken
                "invalid_username" -> ProfileError.InvalidUsername
                "not_authenticated" -> ProfileError.Unauthenticated
                else -> ProfileError.Unknown
            }
        }

        internal fun isPostgrestSchemaCacheError(error: Throwable): Boolean {
            return generateSequence(error) { it.cause }.any { throwable ->
                val message = throwable.message?.lowercase().orEmpty()
                message.contains("pgrst002")
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

        private const val POSTGREST_SCHEMA_CACHE_RETRY_DELAY_MS = 1_500L
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
