package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.api.decodeRpc
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Reads the active Hive co-op challenge + collective progress, and fires contributions to the live
 * `contribute_to_challenge` RPC. Contributions are a fire-and-forget side-effect: they never block
 * or delay the mission/gem result and swallow every error (inactive/not-found/timeout) with a debug
 * log only. The client never calls `finalize_hive_challenge` (server-side payout, service_role).
 */
open class HiveChallengeRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    @Serializable
    private data class ContributeChallengeParams(
        val p_challenge_id: String,
        val p_amount: Int
    )

    @Serializable
    internal data class ContributeChallengeRpcResponse(
        val success: Boolean,
        val error: String? = null
    )

    private data class CachedContribRow(val row: HiveChallengeRow?, val fetchedAtMillis: Long)

    @Volatile
    private var cachedContribRow: CachedContribRow? = null

    /**
     * Reads the currently active challenge (`now()` within `[starts_at, ends_at]`, not yet rewarded)
     * and its collective contribution total. Returns null when none is active.
     */
    open suspend fun getActiveChallenge(): ActiveHiveChallenge? = withContext(Dispatchers.IO) {
        try {
            val nowIso = Instant.now().toString()
            val active = SupabaseClient.client.postgrest[Constants.TABLE_HIVE_CHALLENGES]
                .select {
                    filter {
                        eq("rewarded", false)
                        lte("starts_at", nowIso)
                        gte("ends_at", nowIso)
                    }
                }
                .decodeList<HiveChallengeRow>()
                .minByOrNull { it.ends_at }
                ?: return@withContext null

            val total = totalContribution(loadProgressRows(active.id))
            ActiveHiveChallenge(
                id = active.id,
                title = active.title,
                description = active.description,
                goalType = active.goal_type,
                goalTarget = active.goal_target,
                rewardHoney = active.reward_honey,
                endsAt = active.ends_at,
                totalContribution = total
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Failed to load active hive challenge", e)
            null
        }
    }

    private suspend fun loadProgressRows(challengeId: String): List<HiveChallengeProgressRow> =
        SupabaseClient.client.postgrest[Constants.TABLE_HIVE_CHALLENGE_PROGRESS]
            .select(Columns.list("contribution")) { filter { eq("challenge_id", challengeId) } }
            .decodeList()

    /**
     * Fire-and-forget contribution. No-op (silently) when there is no active challenge or its
     * `goal_type` differs from [actionGoalType]. Detached on an internal scope so the caller's
     * result is never delayed; every failure is logged only.
     */
    /**
     * Fire-and-forget: resolve the active challenge ONCE and contribute the amount mapped to its
     * goal_type, if any. Replaces the per-action pair of contributeIfMatches calls (each of which read
     * the challenge + progress rows) — now one lightweight, TTL-cached read per action (audit M-7).
     * Detached on [scope] so the caller's result is never delayed; failures are logged only.
     */
    open fun contribute(amounts: Map<String, Int>) {
        scope.launch {
            try {
                val challenge = activeChallengeRowForContribution() ?: return@launch
                val amount = amounts[challenge.goal_type] ?: return@launch
                if (amount <= 0) return@launch
                SupabaseClient.client.postgrest.rpc(
                    "contribute_to_challenge",
                    ContributeChallengeParams(challenge.id, amount)
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logError("Hive contribution failed (silent)", e)
            }
        }
    }

    /**
     * Lightweight active-challenge lookup for the contribution path: just the challenge row
     * (id + goal_type), without the progress-row aggregation getActiveChallenge does for the UI.
     * Cached for a short TTL so back-to-back contributions in one action don't re-read.
     */
    private suspend fun activeChallengeRowForContribution(): HiveChallengeRow? {
        val now = System.currentTimeMillis()
        cachedContribRow?.let { if (now - it.fetchedAtMillis < CONTRIB_CACHE_TTL_MS) return it.row }
        val nowIso = Instant.now().toString()
        val row = SupabaseClient.client.postgrest[Constants.TABLE_HIVE_CHALLENGES]
            .select {
                filter {
                    eq("rewarded", false)
                    lte("starts_at", nowIso)
                    gte("ends_at", nowIso)
                }
            }
            .decodeList<HiveChallengeRow>()
            .minByOrNull { it.ends_at }
        cachedContribRow = CachedContribRow(row, now)
        return row
    }

    private fun logError(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            AppLogger.e(
                "HiveChallengeRepository",
                "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}]"
            )
        }
    }

    companion object {
        private const val CONTRIB_CACHE_TTL_MS = 30_000L

        internal fun totalContribution(rows: List<HiveChallengeProgressRow>): Int =
            rows.sumOf { it.contribution }
    }
}
