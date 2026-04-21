package com.novahorizon.wanderly.ui.common

import android.content.Context

object LocationPermissionGate {
    private const val PREFS_NAME = "wanderly_runtime_permissions"
    private const val KEY_FINE_LOCATION_REQUESTED = "fine_location_requested"

    enum class State {
        GRANTED,
        REQUEST,
        RATIONALE,
        SETTINGS
    }

    internal fun resolveState(
        hasPermission: Boolean,
        hasRequestedBefore: Boolean,
        shouldShowRationale: Boolean
    ): State {
        return when {
            hasPermission -> State.GRANTED
            !hasRequestedBefore -> State.REQUEST
            shouldShowRationale -> State.RATIONALE
            else -> State.SETTINGS
        }
    }

    internal fun shouldLaunchSystemRequest(state: State): Boolean {
        return state == State.REQUEST || state == State.RATIONALE
    }

    fun hasRequestedBefore(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FINE_LOCATION_REQUESTED, false)
    }

    fun markRequestedBefore(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FINE_LOCATION_REQUESTED, true)
            .apply()
    }
}
