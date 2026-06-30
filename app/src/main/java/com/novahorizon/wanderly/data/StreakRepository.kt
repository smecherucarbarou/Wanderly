package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.api.decodeRpc
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Streak loss/restore/freeze and milestone catalog/claim. Carved out of ProfileRepository
 * (big_improvements A, 3d). Shares [ProfileStateHolder] + [ProfileProgressWriter]; [getCurrentProfile]
 * re-fetches after server-side reward grants that don't echo the new balance.
 */
class StreakRepository(
    private val profileState: ProfileStateHolder,
    private val progressWriter: ProfileProgressWriter,
    private val getCurrentProfile: suspend () -> Profile?
) {
    @Serializable
    private data class RestoreStreakParams(val cost: Int)

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
    private data class ClaimMilestoneParams(val p_threshold: Int)

    @Serializable
    internal data class StreakMilestoneClaimRpcResponse(
        val success: Boolean,
        val error: String? = null,
        val reward_honey: Int? = null,
        val badge: String? = null
    )

    @Serializable
    private data class StreakMilestoneClaimRow(val threshold: Int)

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
                val profile = progressWriter.apply(
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
                val profile = progressWriter.apply(
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
                val updated = profileState.updateAndGet { it?.copy(streak_freezes = freezesLeft) }
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

            val streak = profileState.value?.streak_count ?: 0
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

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val safeMessage = LogRedactor.redact(message)
            if (throwable != null) {
                AppLogger.e(
                    "StreakRepository",
                    "$safeMessage [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
                )
            } else {
                AppLogger.e("StreakRepository", safeMessage)
            }
        }
    }
}
