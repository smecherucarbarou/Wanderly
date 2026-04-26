package com.novahorizon.wanderly.ui.common

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

class LocationPermissionController(private val fragment: Fragment) {
    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val requestPermissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val hasLocationPermission = fragment.context?.let(LocationPermissionGate::hasLocationPermission) == true
        onPermissionResult?.invoke(isGranted || hasLocationPermission)
        onPermissionResult = null
    }

    fun resolveState(): LocationPermissionGate.State {
        return LocationPermissionGate.resolveState(
            hasPermission = LocationPermissionGate.hasLocationPermission(fragment.requireContext()),
            hasRequestedBefore = LocationPermissionGate.hasRequestedBefore(fragment.requireContext()),
            shouldShowRationale = fragment.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        onPermissionResult = onResult
        LocationPermissionGate.markRequestedBefore(fragment.requireContext())
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun openAppSettings() {
        fragment.startActivity(appSettingsIntent(fragment.requireContext().packageName))
    }

    companion object {
        internal fun appSettingsIntent(packageName: String): Intent {
            return Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        }
    }
}
