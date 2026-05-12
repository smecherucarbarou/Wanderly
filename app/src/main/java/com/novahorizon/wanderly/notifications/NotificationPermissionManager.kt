package com.novahorizon.wanderly.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.content.ContextCompat

object NotificationPermissionManager {
    enum class Status {
        GRANTED,
        DENIED,
        NOT_REQUIRED
    }

    enum class RequestAction {
        NONE,
        REQUEST_PERMISSION,
        SHOW_RATIONALE,
        OPEN_SETTINGS
    }

    fun status(context: Context): Status {
        val isRuntimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val isPermissionGranted = !isRuntimePermissionRequired ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        return resolveStatus(
            isRuntimePermissionRequired = isRuntimePermissionRequired,
            isPermissionGranted = isPermissionGranted
        )
    }

    fun hasNotificationPermission(context: Context): Boolean = status(context) != Status.DENIED

    fun hasRequestedPermissionBefore(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_POST_NOTIFICATIONS_REQUESTED, false)

    fun markPermissionRequested(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_POST_NOTIFICATIONS_REQUESTED, true)
            }
    }

    fun resolveRequestAction(
        status: Status,
        requestedBefore: Boolean,
        shouldShowRationale: Boolean
    ): RequestAction {
        if (status != Status.DENIED) return RequestAction.NONE
        return when {
            shouldShowRationale -> RequestAction.SHOW_RATIONALE
            requestedBefore -> RequestAction.OPEN_SETTINGS
            else -> RequestAction.REQUEST_PERMISSION
        }
    }

    fun notificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }

    internal fun resolveStatus(
        isRuntimePermissionRequired: Boolean,
        isPermissionGranted: Boolean
    ): Status {
        return when {
            !isRuntimePermissionRequired -> Status.NOT_REQUIRED
            isPermissionGranted -> Status.GRANTED
            else -> Status.DENIED
        }
    }

    private const val PREFS_NAME = "wanderly_runtime_permissions"
    private const val KEY_POST_NOTIFICATIONS_REQUESTED = "post_notifications_requested"
}
