package com.novahorizon.wanderly.data

data class WidgetStreakSnapshot(
    val streakCount: Int,
    val lastMissionDate: String?,
    val savedAtMillis: Long,
    val lastSyncSucceeded: Boolean
)
