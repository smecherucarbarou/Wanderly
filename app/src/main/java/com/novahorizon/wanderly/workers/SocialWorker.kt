/* FIXES APPLIED: BUG C, BUG D — see inline comments */
package com.novahorizon.wanderly.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class SocialWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pid = android.os.Process.myPid()
        Log.d("SocialWorker", "--- Social Polling Started (PID: $pid) ---")
        val repository = WanderlyRepository(applicationContext)
        
        try {
            // FIX BUG 2: Wait for Auth before giving up
            delay(5_000L)
            val profile = repository.getCurrentProfile() ?: run {
                Log.w("SocialWorker", "Auth not ready after 5s delay. Scheduling retry.")
                return@withContext Result.retry()
            }
            
            val userHoney = profile.honey ?: 0
            val friends = repository.getFriends()
            
            if (friends.isEmpty()) {
                return@withContext Result.success()
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val today = sdf.format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).time)
            
            val rivalsFinishedToday = friends.filter { it.last_mission_date == today }
            val overtakenBy = friends.filter { (it.honey ?: 0) > userHoney }

            // BUG C FIXED: Rival activity is handled by HiveRealtimeService. 
            // SocialWorker only sends aggregated + overtaken alerts.
            if (rivalsFinishedToday.size > 1) {
                WanderlyNotificationManager.sendAggregatedRivalActivity(
                    applicationContext, 
                    rivalsFinishedToday.size
                )
            }
            
            // Overtaken alert (Sent for the top rival who overtook you)
            if (overtakenBy.isNotEmpty()) {
                val topRival = overtakenBy.maxByOrNull { it.honey ?: 0 }
                WanderlyNotificationManager.sendOvertakenAlert(
                    applicationContext, 
                    topRival?.username ?: "Someone"
                )
            }

            // BUG D FIXED: Trigger sendFightForFirst if a rival is close to overtaking
            val aboutToOvertake = friends.filter { 
                val theirHoney = it.honey ?: 0
                theirHoney < userHoney && theirHoney >= (userHoney * 0.90).toInt()
            }
            if (aboutToOvertake.isNotEmpty()) {
                val topThreat = aboutToOvertake.maxByOrNull { it.honey ?: 0 }
                WanderlyNotificationManager.sendFightForFirst(
                    applicationContext, topThreat?.username ?: "Someone"
                )
            }

            Log.d("SocialWorker", "--- Social Polling Finished ---")
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("SocialWorker", "Error in SocialWorker", e)
            Result.failure()
        }
    }
}
