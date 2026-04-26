package com.novahorizon.wanderly.notifications

import android.content.Context
import android.util.Log
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository

object NotificationCheckCoordinator {
    private const val LOG_TAG = "NotifCheck"

    suspend fun runTimedStreakCheck(
        context: Context,
        repository: WanderlyRepository,
        source: String
    ) {
        StreakNotificationRules.runTimedCheck(
            context = context,
            repository = repository,
            source = source,
            stateStore = NotificationStateStore(context)
        )
    }

    suspend fun runSocialFallbackCheck(
        context: Context,
        repository: WanderlyRepository,
        source: String
    ) {
        SocialNotificationRules.runFallbackCheck(
            context = context,
            repository = repository,
            source = source,
            stateStore = NotificationStateStore(context)
        )
    }

    suspend fun handleRealtimeProfileUpdate(
        context: Context,
        repository: WanderlyRepository,
        currentProfile: Profile,
        updatedProfile: Profile
    ) {
        SocialNotificationRules.handleRealtimeProfileUpdate(
            context = context,
            repository = repository,
            currentProfile = currentProfile,
            updatedProfile = updatedProfile,
            stateStore = NotificationStateStore(context)
        )
    }

    fun log(category: String, source: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, "[$category][$source] $message")
        }
    }

    suspend fun clearCheckState(context: Context) {
        NotificationStateStore(context).clearAll()
        log("debug", "admin_panel", "Notification check state cleared.")
    }

    suspend fun clearCheckStateForType(
        context: Context,
        type: WanderlyNotificationManager.NotificationType
    ) {
        NotificationStateStore(context).clearForType(type)
        log("debug", "admin_panel", "Notification check state cleared for $type.")
    }
}
