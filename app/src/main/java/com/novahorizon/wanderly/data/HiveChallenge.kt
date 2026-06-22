package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

/** A row from the `hive_challenges` table (matches live schema). */
@Serializable
data class HiveChallengeRow(
    val id: String,
    val title: String,
    val description: String? = null,
    val goal_type: String,
    val goal_target: Int,
    val reward_honey: Int = 0,
    val starts_at: String,
    val ends_at: String,
    val rewarded: Boolean = false
)

/** `hive_challenge_progress` projection: only the per-user contribution is summed client-side. */
@Serializable
data class HiveChallengeProgressRow(
    val contribution: Int
)

/** Action categories that map to `hive_challenges.goal_type`. */
object HiveGoalType {
    const val MISSIONS = "missions"
    const val GEMS = "gems"
    const val HONEY = "honey"
}

/**
 * UI-facing active challenge: the live row plus the collective contribution total (sum of every
 * member's `hive_challenge_progress`, visible to all via RLS).
 */
data class ActiveHiveChallenge(
    val id: String,
    val title: String,
    val description: String?,
    val goalType: String,
    val goalTarget: Int,
    val rewardHoney: Int,
    val endsAt: String,
    val totalContribution: Int
) {
    val progressFraction: Float
        get() = if (goalTarget <= 0) 0f else (totalContribution.toFloat() / goalTarget).coerceIn(0f, 1f)

    val goalReached: Boolean
        get() = totalContribution >= goalTarget
}
