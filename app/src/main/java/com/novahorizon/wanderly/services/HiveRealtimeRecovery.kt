package com.novahorizon.wanderly.services

internal data class HiveRealtimeProfileSubscription(
    val channelId: String,
    val profileId: String
)

internal object HiveRealtimeSubscriptionPlanner {
    fun subscriptionsFor(
        currentUserId: String,
        friendIds: List<String>
    ): List<HiveRealtimeProfileSubscription> {
        val normalizedCurrentUserId = currentUserId.trim()
        return friendIds
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != normalizedCurrentUserId }
            .distinct()
            .map { profileId ->
                HiveRealtimeProfileSubscription(
                    channelId = "hive_updates_${Integer.toHexString(profileId.hashCode())}",
                    profileId = profileId
                )
            }
    }
}

internal enum class RealtimeRecoveryAction {
    RetryInvalidSubscription,
    RefreshTokenAndRetry,
    RetryNetworkLater,
    DisableRealtimeForSession
}

internal class HiveRealtimeReconnectPolicy(
    private val maxInvalidSubscriptionRetries: Int = 3,
    private val maxAuthTokenRefreshes: Int = 1,
    private val maxNetworkRetries: Int = 3
) {
    var realtimeDisabledForSession: Boolean = false
        private set
    private var invalidSubscriptionFailures = 0
    private var authTokenRefreshes = 0
    private var networkFailures = 0

    fun recordSubscriptionFailure(error: Throwable): RealtimeRecoveryAction {
        if (realtimeDisabledForSession) return RealtimeRecoveryAction.DisableRealtimeForSession

        val message = listOfNotNull(error.message, error.cause?.message)
            .joinToString(" ")
            .lowercase()

        if (isAuthTokenError(message)) {
            authTokenRefreshes += 1
            return if (authTokenRefreshes <= maxAuthTokenRefreshes) {
                RealtimeRecoveryAction.RefreshTokenAndRetry
            } else {
                disableForSession()
            }
        }

        if (isInvalidSubscriptionError(message)) {
            invalidSubscriptionFailures += 1
            return if (invalidSubscriptionFailures < maxInvalidSubscriptionRetries) {
                RealtimeRecoveryAction.RetryInvalidSubscription
            } else {
                disableForSession()
            }
        }

        networkFailures += 1
        return if (networkFailures <= maxNetworkRetries) {
            RealtimeRecoveryAction.RetryNetworkLater
        } else {
            disableForSession()
        }
    }

    fun networkRetryDelayMs(retryIndex: Int): Long {
        val multiplier = 1 shl retryIndex.coerceIn(0, 5)
        return (1_000L * multiplier).coerceAtMost(30_000L)
    }

    fun resetAfterSuccessfulSubscription() {
        invalidSubscriptionFailures = 0
        authTokenRefreshes = 0
        networkFailures = 0
    }

    fun disableForSession(): RealtimeRecoveryAction {
        realtimeDisabledForSession = true
        return RealtimeRecoveryAction.DisableRealtimeForSession
    }

    private fun isInvalidSubscriptionError(message: String): Boolean =
        message.contains("unable to subscribe to changes") ||
            message.contains("given parameters") ||
            message.contains("invalid filter") ||
            message.contains("postgres_changes")

    private fun isAuthTokenError(message: String): Boolean =
        message.contains("jwt") ||
            message.contains("access token") ||
            message.contains("token expired") ||
            message.contains("expired")
}
