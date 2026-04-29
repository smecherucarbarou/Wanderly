package com.novahorizon.wanderly.services

import com.novahorizon.wanderly.observability.AppLogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.SocialRepository
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.notifications.NotificationCheckCoordinator
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class HiveRealtimeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: WanderlyRepository
    private val socialRepository = SocialRepository()
    private val realtimeChannels = mutableListOf<RealtimeChannel>()
    private val realtimeCollectorJobs = mutableListOf<Job>()
    private val reconnectPolicy = HiveRealtimeReconnectPolicy()
    private var statusCollectorJob: Job? = null
    private var isSubscribed = false
    private var networkRetryAttempt = 0
    private var realtimeDisabledForSession = !ENABLE_PROFILE_REALTIME
    private var realtimeSetupFailures = 0
    private val maxRealtimeSetupFailures = 2
    private var realtimeSetupGeneration = 0

    override fun onCreate() {
        super.onCreate()
        repository = WanderlyGraph.repository(this)
        startForegroundService()
        observeHiveChanges()
    }

    private fun startForegroundService() {
        val channelId = "hive_realtime_service"
        val channel = NotificationChannel(
            channelId, "Hive Realtime Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps you connected to the hive for instant alerts" }
        val manager = getSystemService(NotificationManager::class.java) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wanderly is Active")
            .setContentText("Watching the hive for rival activity...")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1002, notification)
        }
    }

    private fun observeHiveChanges() {
        serviceScope.launch {
            try {
                if (!ENABLE_PROFILE_REALTIME) {
                    logInfo("Profile realtime disabled; using manual refresh fallback.")
                    return@launch
                }
                val profile = repository.getCurrentProfile() ?: run {
                    logWarn("No profile found, stopping service.")
                    stopSelf()
                    return@launch
                }

                SupabaseClient.client.realtime.connect()

                statusCollectorJob?.cancel()
                statusCollectorJob = SupabaseClient.client.realtime.status.onEach { status ->
                    logDebug("Connection Status: $status")
                    if (realtimeDisabledForSession || reconnectPolicy.realtimeDisabledForSession) {
                        return@onEach
                    }
                    if (status == Realtime.Status.CONNECTED) {
                        if (!isSubscribed) {
                            logDebug("Connected! Subscribing to channel...")
                            subscribeWithFallback(profile.id)
                        }
                    } else {
                        isSubscribed = false
                    }
                }.launchIn(serviceScope)

            } catch (e: Exception) {
                logError("Critical error in service", e)
            }
        }
    }

    private suspend fun subscribeWithFallback(currentUserId: String) {
        try {
            setupSubscription(currentUserId)
            if (realtimeDisabledForSession || reconnectPolicy.realtimeDisabledForSession) {
                isSubscribed = false
                return
            }
            reconnectPolicy.resetAfterSuccessfulSubscription()
            networkRetryAttempt = 0
            isSubscribed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleSubscriptionFailure(currentUserId, e)
        }
    }

    private suspend fun setupSubscription(currentUserId: String) {
        try {
            setupSubscriptionInternal(currentUserId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            logWarn("Realtime disabled for this session: ${e.message}")
            disableRealtimeForSession()
        } catch (e: Exception) {
            logWarn("Realtime setup failed; falling back to manual refresh: ${e.message}")
            disableRealtimeForSession()
        }
    }

    private suspend fun setupSubscriptionInternal(currentUserId: String) {
        if (!ENABLE_PROFILE_REALTIME || realtimeDisabledForSession) {
            logInfo("Profile realtime disabled; using manual refresh fallback.")
            disableRealtimeForSession()
            return
        }
        clearRealtimeSubscriptions()
        val setupGeneration = ++realtimeSetupGeneration

        val subscriptions = HiveRealtimeSubscriptionPlanner.subscriptionsFor(
            currentUserId = currentUserId,
            friendIds = socialRepository.getAcceptedFriendIds()
        )

        if (subscriptions.isEmpty()) {
            logDebug("No accepted friends found; Realtime profile subscriptions are idle.")
            return
        }

        for (subscription in subscriptions) {
            val channel = SupabaseClient.client.realtime.channel("${subscription.channelId}_$setupGeneration")
            realtimeChannels += channel

            val profileChanges = channel.postgresChangeFlow<PostgresAction.Update>(
                schema = "public"
            ) {
                table = Constants.TABLE_PROFILES
                filter("id", FilterOperator.EQ, subscription.profileId)
            }

            val subscribed = withTimeoutOrNull(SUBSCRIPTION_JOIN_TIMEOUT_MS) {
                channel.subscribe(blockUntilSubscribed = true)
                true
            } ?: false
            if (!subscribed) {
                throw IllegalStateException("Unable to subscribe to changes with given parameters before timeout")
            }

            profileChanges.onEach { change ->
                val updatedProfile = change.decodeRecord<Profile>()
                if (updatedProfile.id == currentUserId) return@onEach

                val currentProfile = repository.getCurrentProfile() ?: return@onEach

                logDebug("Realtime profile update received.")
                NotificationCheckCoordinator.handleRealtimeProfileUpdate(
                    context = applicationContext,
                    repository = repository,
                    currentProfile = currentProfile,
                    updatedProfile = updatedProfile
                )
            }.launchIn(serviceScope).also { realtimeCollectorJobs += it }
        }
    }

    private suspend fun handleSubscriptionFailure(currentUserId: String, error: Exception) {
        clearRealtimeSubscriptions()
        when (reconnectPolicy.recordSubscriptionFailure(error)) {
            RealtimeRecoveryAction.RefreshTokenAndRetry -> {
                logWarn("Realtime auth token rejected; refreshing once before retry.")
                val refreshed = runCatching {
                    SupabaseClient.client.auth.refreshCurrentSession()
                    SupabaseClient.client.realtime.setAuth()
                }.isSuccess
                if (refreshed) {
                    subscribeWithFallback(currentUserId)
                } else {
                    disableRealtimeForSession()
                    logWarn("Realtime auth refresh failed; using refresh-only fallback for this session.")
                }
            }
            RealtimeRecoveryAction.RetryInvalidSubscription -> {
                logWarn("Realtime profile subscription was rejected; retrying with bounded fallback policy.")
                delay(reconnectPolicy.networkRetryDelayMs(0))
                subscribeWithFallback(currentUserId)
            }
            RealtimeRecoveryAction.RetryNetworkLater -> {
                val delayMs = reconnectPolicy.networkRetryDelayMs(networkRetryAttempt++)
                logWarn("Realtime unavailable; retrying after backoff while app uses refresh fallback.")
                delay(delayMs)
                subscribeWithFallback(currentUserId)
            }
            RealtimeRecoveryAction.DisableRealtimeForSession -> {
                disableRealtimeForSession()
                logWarn("Realtime disabled for this session; app will continue with manual and screen refresh.")
            }
        }
    }

    private suspend fun clearRealtimeSubscriptions() {
        realtimeCollectorJobs.forEach { it.cancel() }
        realtimeCollectorJobs.clear()

        val channels = realtimeChannels.toList()
        realtimeChannels.clear()
        channels.forEach { channel ->
            runCatching {
                withTimeoutOrNull(2_000L) {
                    SupabaseClient.client.realtime.removeChannel(channel)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        statusCollectorJob?.cancel()
        serviceScope.launch {
            try {
                clearRealtimeSubscriptions()
                logDebug("Unsubscribed from channel.")
            } catch (e: Exception) {
                logError("Error during unsubscribe", e)
            } finally {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d("HiveRealtime", LogRedactor.redact(message))
        }
    }

    private fun logWarn(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.w("HiveRealtime", LogRedactor.redact(message))
        }
    }

    private fun logInfo(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.i("HiveRealtime", LogRedactor.redact(message))
        }
    }

    private fun disableRealtimeForSession() {
        realtimeSetupFailures = (realtimeSetupFailures + 1).coerceAtMost(maxRealtimeSetupFailures)
        realtimeDisabledForSession = true
        reconnectPolicy.disableForSession()
        isSubscribed = false
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val safeMessage = LogRedactor.redact(message)
            if (throwable != null) {
                AppLogger.e("HiveRealtime", "$safeMessage [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]")
            } else {
                AppLogger.e("HiveRealtime", safeMessage)
            }
        }
    }

    private companion object {
        private const val ENABLE_PROFILE_REALTIME = false
        const val SUBSCRIPTION_JOIN_TIMEOUT_MS = 7_000L
    }
}
