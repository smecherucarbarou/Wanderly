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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class HiveRealtimeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: WanderlyRepository
    private val socialRepository = SocialRepository()
    private var realtimeChannel: RealtimeChannel? = null
    private var realtimeCollectorJob: Job? = null
    private var statusCollectorJob: Job? = null
    private var isSubscribed = false

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
                val profile = repository.getCurrentProfile() ?: run {
                    logWarn("No profile found, stopping service.")
                    stopSelf()
                    return@launch
                }

                SupabaseClient.client.realtime.connect()

                statusCollectorJob?.cancel()
                statusCollectorJob = SupabaseClient.client.realtime.status.onEach { status ->
                    logDebug("Connection Status: $status")
                    if (status == Realtime.Status.CONNECTED) {
                        if (!isSubscribed) {
                            logDebug("Connected! Subscribing to channel...")
                            setupSubscription(profile.id)
                            isSubscribed = true
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

    private suspend fun setupSubscription(currentUserId: String) {
        realtimeCollectorJob?.cancel()
        realtimeChannel?.unsubscribe()

        val channel = SupabaseClient.client.realtime.channel("hive_updates")
        realtimeChannel = channel
        val relevantIds = buildList {
            add(currentUserId)
            addAll(socialRepository.getAcceptedFriendIds())
        }.distinct()

        channel.postgresChangeFlow<PostgresAction.Update>(
            schema = "public"
        ) {
            table = Constants.TABLE_PROFILES
            filter("id", FilterOperator.IN, relevantIds)
        }.onEach { change ->
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
        }.launchIn(serviceScope).also { realtimeCollectorJob = it }

        channel.subscribe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        realtimeCollectorJob?.cancel()
        statusCollectorJob?.cancel()
        serviceScope.launch {
            try {
                withTimeoutOrNull(2_000L) {
                    realtimeChannel?.unsubscribe()
                }
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
}
