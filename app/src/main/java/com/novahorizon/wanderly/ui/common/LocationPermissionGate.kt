package com.novahorizon.wanderly.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

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
        // Non-sensitive: stores only whether the permission rationale was shown
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FINE_LOCATION_REQUESTED, false)
    }

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun markRequestedBefore(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FINE_LOCATION_REQUESTED, true)
            .apply()
    }
}
