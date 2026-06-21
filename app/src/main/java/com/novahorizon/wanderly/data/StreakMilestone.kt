package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

/** A row from the `streak_milestones` catalog table. */
@Serializable
data class StreakMilestone(
    val threshold: Int,
    val title: String = "",
    val reward_honey: Int = 0
)

/** UI-facing milestone state combining the catalog row with the viewer's progress and claims. */
data class StreakMilestoneStatus(
    val threshold: Int,
    val title: String,
    val rewardHoney: Int,
    val reached: Boolean,
    val claimed: Boolean
) {
    val claimable: Boolean get() = reached && !claimed
}
