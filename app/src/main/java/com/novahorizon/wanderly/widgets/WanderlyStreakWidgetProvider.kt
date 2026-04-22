package com.novahorizon.wanderly.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import com.novahorizon.wanderly.MainActivity
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.data.PreferencesStore
import com.novahorizon.wanderly.data.WidgetStreakSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WanderlyStreakWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAsync(context) {
            refreshAndRenderWidgets(context, appWidgetManager, appWidgetIds)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_REFRESH_WIDGET) {
            updateAsync(context) {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, WanderlyStreakWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
                if (appWidgetIds.isNotEmpty()) {
                    refreshAndRenderWidgets(context, appWidgetManager, appWidgetIds)
                }
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleNextUpdate(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelScheduledUpdates(context)
    }

    private fun updateAsync(context: Context, block: suspend () -> Unit) {
        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                block()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REFRESH_WIDGET = "com.novahorizon.wanderly.widgets.ACTION_REFRESH_WIDGET"
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val fallbackHandler = Handler(Looper.getMainLooper())
        @Volatile
        private var fallbackRunnable: Runnable? = null

        private suspend fun refreshAndRenderWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val appContext = context.applicationContext
            val preferencesStore = PreferencesStore(appContext)
            val repository = WanderlyGraph.repository(appContext)
            val nowMillis = System.currentTimeMillis()

            val fetchedSnapshot = try {
                repository.getCurrentProfile()?.let { profile ->
                    WidgetStreakSnapshot(
                        streakCount = profile.streak_count ?: 0,
                        lastMissionDate = profile.last_mission_date,
                        savedAtMillis = nowMillis,
                        lastSyncSucceeded = true
                    ).also { snapshot ->
                        try {
                            preferencesStore.saveWidgetStreakSnapshot(snapshot)
                        } catch (_: Exception) {
                            // Keep the fresh fetch for rendering even if persistence fails.
                        }
                    }
                }
            } catch (_: Exception) {
                null
            }

            val snapshotToRender = fetchedSnapshot ?: run {
                try {
                    preferencesStore.getWidgetStreakSnapshot()
                } catch (_: Exception) {
                    null
                }
            }
            val currentFetchSucceeded = fetchedSnapshot != null
            val visualState = StreakWidgetStateHelper.resolveVisualState(
                snapshot = snapshotToRender,
                currentFetchSucceeded = currentFetchSucceeded
            )

            appWidgetIds.forEach { appWidgetId ->
                val views = RemoteViews(appContext.packageName, R.layout.widget_streak).apply {
                    setTextViewText(R.id.widget_streak_count, (snapshotToRender?.streakCount ?: 0).toString())
                    setTextViewText(R.id.widget_subtitle, appContext.getString(visualState.subtitleRes))
                    setImageViewResource(R.id.widget_mascot, visualState.mascotRes)
                    setImageViewResource(R.id.widget_fire_icon, visualState.fireRes)
                    setTextColor(
                        R.id.widget_streak_count,
                        appContext.getColor(visualState.countColorRes)
                    )
                    setTextColor(
                        R.id.widget_subtitle,
                        appContext.getColor(visualState.subtitleColorRes)
                    )
                    setInt(
                        R.id.widget_container,
                        "setBackgroundResource",
                        visualState.backgroundRes
                    )
                    setOnClickPendingIntent(R.id.widget_container, mainActivityPendingIntent(appContext))
                }
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }

            scheduleNextUpdate(appContext)
        }

        private fun scheduleNextUpdate(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = refreshPendingIntent(appContext)
            StreakWidgetAlarmScheduler.scheduleNext(alarmManager, pendingIntent)
            scheduleFallbackTicker(appContext)
        }

        private fun cancelScheduledUpdates(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            StreakWidgetAlarmScheduler.cancel(alarmManager, refreshPendingIntent(appContext))
            fallbackRunnable?.let(fallbackHandler::removeCallbacks)
            fallbackRunnable = null
        }

        private fun refreshPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WanderlyStreakWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            return PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun scheduleFallbackTicker(context: Context) {
            fallbackRunnable?.let(fallbackHandler::removeCallbacks)
            val appContext = context.applicationContext
            val runnable = Runnable {
                fallbackRunnable = null
                appContext.sendBroadcast(
                    Intent(appContext, WanderlyStreakWidgetProvider::class.java).apply {
                        action = ACTION_REFRESH_WIDGET
                    }
                )
            }
            fallbackRunnable = runnable
            fallbackHandler.postDelayed(runnable, StreakWidgetAlarmScheduler.REFRESH_INTERVAL_MILLIS)
        }

        private fun mainActivityPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                1002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
