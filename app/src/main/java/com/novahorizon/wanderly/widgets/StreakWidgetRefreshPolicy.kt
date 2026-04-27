package com.novahorizon.wanderly.widgets

import com.novahorizon.wanderly.data.WidgetStreakSnapshot

object StreakWidgetRefreshPolicy {
    const val REFRESH_INTERVAL_MILLIS = 60 * 60 * 1000L
    const val FLEX_WINDOW_MILLIS = 15 * 60 * 1000L
    const val MIN_REMOTE_REFRESH_INTERVAL_MILLIS = 30 * 60 * 1000L

    fun shouldFetchRemote(snapshot: WidgetStreakSnapshot?, nowMillis: Long): Boolean {
        if (snapshot == null) return true
        if (!snapshot.lastSyncSucceeded) return true
        return nowMillis - snapshot.savedAtMillis >= MIN_REMOTE_REFRESH_INTERVAL_MILLIS
    }
}
