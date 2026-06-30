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
 * Referral lookup + claim. Carved out of ProfileRepository (big_improvements A, 3d). The claim RPC
 * credits honey to both parties server-side without echoing the new balance, so [getCurrentProfile]
 * re-fetches the profile afterward.
 */
class ReferralRepository(
    private val getCurrentProfile: suspend () -> Profile?
) {
    @Serializable
    private data class ReferralClaimParams(val p_friend_code: String)

    @Serializable
    internal data class ReferralClaimRpcResponse(
        val success: Boolean,
        val error: String? = null,
        val reward_honey: Int? = null
    )

    @Serializable
    private data class ReferralRow(val referred_id: String)

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
                // balance, so re-fetch the profile. Reward amount is carried in the reason.
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

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val safeMessage = LogRedactor.redact(message)
            if (throwable != null) {
                AppLogger.e(
                    "ReferralRepository",
                    "$safeMessage [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
                )
            } else {
                AppLogger.e("ReferralRepository", safeMessage)
            }
        }
    }
}
