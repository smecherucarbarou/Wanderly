/* FIXES APPLIED: BUG B, BUG C — see inline comments */
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
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HiveRealtimeService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repository: WanderlyRepository
    private var realtimeChannel: RealtimeChannel? = null // BUG 5 FIXED: Class-level field
    private var isSubscribed = false // BUG B FIXED: Track subscription status to prevent duplication

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
            .setSmallIcon(R.drawable.ic_notification) // BUG 12 FIXED: Use app icon
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

                // BUG 4 FIXED: Connect before subscribing
                SupabaseClient.client.realtime.connect()

                // BUG 6 FIXED: Re-subscribe on reconnect
                // BUG B FIXED: Use isSubscribed flag to prevent multiple parallel listeners
                SupabaseClient.client.realtime.status.onEach { status ->
                    Log.d("HiveRealtime", "Connection Status: $status")
                    if (status == Realtime.Status.CONNECTED) {
                        if (!isSubscribed) {
                            Log.d("HiveRealtime", "Connected! Subscribing to channel...")
                            setupSubscription(profile)
                            isSubscribed = true
                        }
                    } else {
                        // Reset flag on any non-connected status so we can re-subscribe when CONNECTED returns
                        isSubscribed = false
                    }
                }.launchIn(this)

                // Keep-alive / Reconnect loop
                while (isActive) {
                    delay(30000)
                    if (SupabaseClient.client.realtime.status.value != Realtime.Status.CONNECTED) {
                        Log.w("HiveRealtime", "Not connected, attempting reconnection...")
                        SupabaseClient.client.realtime.connect()
                    }
                }
            } catch (e: Exception) {
                Log.e("HiveRealtime", "Critical error in service", e)
            }
        }
    }

    private suspend fun setupSubscription(profile: Profile) {
        // Clean up old channel if it exists
        realtimeChannel?.unsubscribe()
        
        val channel = SupabaseClient.client.realtime.channel("hive_updates")
        realtimeChannel = channel

        channel.postgresChangeFlow<PostgresAction.Update>(
            schema = "public"
        ) {
            table = Constants.TABLE_PROFILES
        }.onEach { change ->
            val updatedProfile = change.decodeRecord<Profile>()
            if (updatedProfile.id == profile.id) return@onEach

            Log.d("HiveRealtime", "Realtime update from: ${updatedProfile.username}")

            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val today = sdf.format(Date())

            // Logic 1: Rival Activity
            if (updatedProfile.last_mission_date == today) {
                // BUG 8 FIXED: shared cooldown is handled in WanderlyNotificationManager
                WanderlyNotificationManager.sendRivalActivity(applicationContext, updatedProfile.username ?: "A rival")
            }

            // Logic 2: Overtaken
            if ((updatedProfile.honey ?: 0) > (profile.honey ?: 0)) {
                WanderlyNotificationManager.sendOvertakenAlert(applicationContext, updatedProfile.username ?: "Someone")
            }
        }.launchIn(serviceScope)

        channel.subscribe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.launch {
            try {
                // BUG 5 FIXED: Explicitly unsubscribe
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
