package com.novahorizon.wanderly.notifications

import android.content.Context
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.util.DateUtils
import java.util.Date

object SocialNotificationRules {
    suspend fun runFallbackCheck(
        context: Context,
        repository: WanderlyRepository,
        source: String,
        stateStore: NotificationStateStore
    ) {
        val currentProfile = repository.getCurrentProfile()
        if (currentProfile == null) {
            NotificationCheckCoordinator.log("social", source, "Skipped: no current profile.")
            return
        }

        val friends = repository.getFriends()
        if (friends.isEmpty()) {
            stateStore.clearTopState()
            NotificationCheckCoordinator.log("social", source, "Skipped: no friends to track.")
            return
        }

        val today = utcDate()
        val rivalsFinishedToday = friends.filter { it.last_mission_date == today }
        val userHoney = currentProfile.honey ?: 0

        when (rivalsFinishedToday.size) {
            0 -> NotificationCheckCoordinator.log("social", source, "No rival missions completed today.")
            1 -> {
                val rival = rivalsFinishedToday.first()
                if (stateStore.markRivalMissionIfNew(today, rival.id)) {
                    WanderlyNotificationManager.sendRivalActivity(
                        context,
                        rival.username ?: "A rival"
                    )
                    NotificationCheckCoordinator.log("social", source, "Sent single-rival activity for ${rival.id}.")
                } else {
                    NotificationCheckCoordinator.log("social", source, "Single-rival activity already handled for ${rival.id}.")
                }
            }

            else -> {
                val signature = rivalsFinishedToday
                    .map { it.id }
                    .sorted()
                    .joinToString(",")
                if (stateStore.hasAggregateChanged(today, signature)) {
                    val posted = WanderlyNotificationManager.sendAggregatedRivalActivity(
                        context,
                        rivalsFinishedToday.size
                    )
                    if (posted) {
                        stateStore.persistAggregateState(today, signature)
                        NotificationCheckCoordinator.log("social", source, "Sent grouped rival activity for ${rivalsFinishedToday.size} rivals.")
                    } else {
                        NotificationCheckCoordinator.log("social", source, "Grouped rival activity suppressed for ${rivalsFinishedToday.size} rivals.")
                    }
                } else {
                    NotificationCheckCoordinator.log("social", source, "Grouped rival activity already handled for signature=$signature.")
                }
            }
        }

        val topRival = friends
            .filter { (it.honey ?: 0) > userHoney }
            .maxByOrNull { it.honey ?: 0 }
        handleOvertakenState(context, topRival, source, stateStore)

        if (topRival != null) {
            stateStore.clearThreatRivalId()
            NotificationCheckCoordinator.log("social", source, "Skipping fight-for-first because a rival is already ahead.")
            return
        }

        val topThreat = friends
            .filter {
                val theirHoney = it.honey ?: 0
                theirHoney < userHoney && theirHoney >= (userHoney * 0.90).toInt()
            }
            .maxByOrNull { it.honey ?: 0 }
        handleThreatState(context, topThreat, source, stateStore)
    }

    suspend fun handleRealtimeProfileUpdate(
        context: Context,
        repository: WanderlyRepository,
        currentProfile: Profile,
        updatedProfile: Profile,
        stateStore: NotificationStateStore
    ) {
        val today = utcDate()
        val source = "service_realtime"
        val currentHoney = currentProfile.honey ?: 0
        val updatedHoney = updatedProfile.honey ?: 0

        if (updatedProfile.last_mission_date == today &&
            stateStore.markRivalMissionIfNew(today, updatedProfile.id)
        ) {
            WanderlyNotificationManager.sendRivalActivity(
                context,
                updatedProfile.username ?: "A rival"
            )
            NotificationCheckCoordinator.log("social", source, "Realtime rival mission from ${updatedProfile.id}.")
        }

        val friends = repository.getFriends()
        if (friends.isEmpty()) {
            stateStore.clearTopState()
            NotificationCheckCoordinator.log("social", source, "Realtime update ignored because there are no friends.")
            return
        }

        val topRival = friends
            .filter { (it.honey ?: 0) > currentHoney }
            .maxByOrNull { it.honey ?: 0 }

        if (topRival != null) {
            stateStore.clearThreatRivalId()
            handleOvertakenState(context, topRival, source, stateStore)
            NotificationCheckCoordinator.log(
                "social",
                source,
                "Skipping fight-for-first because ${topRival.id} is currently ahead."
            )
            return
        }

        val topThreat = friends
            .filter {
                val theirHoney = it.honey ?: 0
                theirHoney < currentHoney && theirHoney >= (currentHoney * 0.90).toInt()
            }
            .maxByOrNull { it.honey ?: 0 }

        if (topThreat != null && (updatedHoney < currentHoney && updatedHoney >= (currentHoney * 0.90).toInt())) {
            handleThreatState(context, topThreat, source, stateStore)
            return
        }

        NotificationCheckCoordinator.log(
            "social",
            source,
            "Realtime update from ${updatedProfile.id} did not change the leading social state."
        )
    }

    private suspend fun handleOvertakenState(
        context: Context,
        topRival: Profile?,
        source: String,
        stateStore: NotificationStateStore
    ) {
        val previousId = stateStore.getOvertakenRivalId()

        if (topRival == null) {
            if (previousId != null) {
                stateStore.clearOvertakenRivalId()
            }
            NotificationCheckCoordinator.log("social", source, "No rival currently ahead.")
            return
        }

        if (topRival.id == previousId) {
            NotificationCheckCoordinator.log("social", source, "Top overtaken rival unchanged: ${topRival.id}.")
            return
        }

        val posted = WanderlyNotificationManager.sendOvertakenAlert(
            context,
            topRival.username ?: "Someone"
        )
        if (posted) {
            stateStore.setOvertakenRivalId(topRival.id)
            NotificationCheckCoordinator.log("social", source, "Sent overtaken alert for ${topRival.id}.")
        } else {
            NotificationCheckCoordinator.log("social", source, "Overtaken alert suppressed for ${topRival.id}.")
        }
    }

    private suspend fun handleThreatState(
        context: Context,
        topThreat: Profile?,
        source: String,
        stateStore: NotificationStateStore
    ) {
        val previousId = stateStore.getThreatRivalId()

        if (topThreat == null) {
            if (previousId != null) {
                stateStore.clearThreatRivalId()
            }
            NotificationCheckCoordinator.log("social", source, "No near-overtake threat right now.")
            return
        }

        if (topThreat.id == previousId) {
            NotificationCheckCoordinator.log("social", source, "Top threat unchanged: ${topThreat.id}.")
            return
        }

        val posted = WanderlyNotificationManager.sendFightForFirst(
            context,
            topThreat.username ?: "Someone"
        )
        if (posted) {
            stateStore.setThreatRivalId(topThreat.id)
            NotificationCheckCoordinator.log("social", source, "Sent fight-for-first alert for ${topThreat.id}.")
        } else {
            NotificationCheckCoordinator.log("social", source, "Fight-for-first alert suppressed for ${topThreat.id}.")
        }
    }

    private fun utcDate(): String = DateUtils.formatUtcDate(Date())
}
