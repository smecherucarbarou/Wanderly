package com.novahorizon.wanderly.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.notifications.NotificationCheckCoordinator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HiveRealtimeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: WanderlyRepository
    private var realtimeChannel: RealtimeChannel? = null
    private var isSubscribed = false

    override fun onCreate() {
        super.onCreate()
        repository = WanderlyRepository(this)
        startForegroundService()
        observeHiveChanges()
    }

    private fun startForegroundService() {
        val channelId = "hive_realtime_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Hive Realtime Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps you connected to the hive for instant alerts" }
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(channel)
        }

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
                    Log.w("HiveRealtime", "No profile found, stopping service.")
                    stopSelf()
                    return@launch
                }

                SupabaseClient.client.realtime.connect()

                SupabaseClient.client.realtime.status.onEach { status ->
                    Log.d("HiveRealtime", "Connection Status: $status")
                    if (status == Realtime.Status.CONNECTED) {
                        if (!isSubscribed) {
                            Log.d("HiveRealtime", "Connected! Subscribing to channel...")
                            setupSubscription(profile.id)
                            isSubscribed = true
                        }
                    } else {
                        isSubscribed = false
                    }
                }.launchIn(this)

                while (isActive) {
                    NotificationCheckCoordinator.runTimedStreakCheck(
                        context = applicationContext,
                        repository = repository,
                        source = "service_timer"
                    )
                    NotificationCheckCoordinator.runSocialFallbackCheck(
                        context = applicationContext,
                        repository = repository,
                        source = "service_timer"
                    )
                    if (SupabaseClient.client.realtime.status.value != Realtime.Status.CONNECTED) {
                        Log.w("HiveRealtime", "Not connected, attempting reconnection...")
                        SupabaseClient.client.realtime.connect()
                    }
                    delay(20_000)
                }
            } catch (e: Exception) {
                Log.e("HiveRealtime", "Critical error in service", e)
            }
        }
    }

    private suspend fun setupSubscription(currentUserId: String) {
        realtimeChannel?.unsubscribe()

        val channel = SupabaseClient.client.realtime.channel("hive_updates")
        realtimeChannel = channel

        channel.postgresChangeFlow<PostgresAction.Update>(
            schema = "public"
        ) {
            table = Constants.TABLE_PROFILES
        }.onEach { change ->
            val updatedProfile = change.decodeRecord<Profile>()
            if (updatedProfile.id == currentUserId) return@onEach

            val currentProfile = repository.getCurrentProfile() ?: return@onEach

            Log.d("HiveRealtime", "Realtime update from: ${updatedProfile.username}")
            NotificationCheckCoordinator.handleRealtimeProfileUpdate(
                context = applicationContext,
                repository = repository,
                currentProfile = currentProfile,
                updatedProfile = updatedProfile
            )
        }.launchIn(serviceScope)

        channel.subscribe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.launch {
            try {
                realtimeChannel?.unsubscribe()
                Log.d("HiveRealtime", "Unsubscribed from channel.")
            } catch (e: Exception) {
                Log.e("HiveRealtime", "Error during unsubscribe", e)
            } finally {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }
}
