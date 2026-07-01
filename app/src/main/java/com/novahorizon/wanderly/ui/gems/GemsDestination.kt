package com.novahorizon.wanderly.ui.gems

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Gem
import com.novahorizon.wanderly.showColoredSnackbar
import com.novahorizon.wanderly.ui.common.LocationPermissionGate
import com.novahorizon.wanderly.ui.compose.screens.map.GemsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val GEMS_LOCATION_UPDATE_INTERVAL_MS = 5000L

@Composable
fun GemsDestination(
    snackbarHostState: SnackbarHostState,
    viewModel: GemsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var pendingPermissionResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val hasPermission = LocationPermissionGate.hasLocationPermission(context)
        pendingPermissionResult?.invoke(isGranted || hasPermission)
        pendingPermissionResult = null
    }

    fun resolvePermissionState(): LocationPermissionGate.State {
        return LocationPermissionGate.resolveState(
            hasPermission = LocationPermissionGate.hasLocationPermission(context),
            hasRequestedBefore = LocationPermissionGate.hasRequestedBefore(context),
            shouldShowRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
            } ?: false
        )
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        pendingPermissionResult = onResult
        LocationPermissionGate.markRequestedBefore(context)
        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun notifySnackbar(message: String, isError: Boolean) {
        coroutineScope.launch {
            snackbarHostState.showColoredSnackbar(message, isError)
        }
    }

    fun showLocationPermissionFeedback() {
        val messageRes = when (resolvePermissionState()) {
            LocationPermissionGate.State.RATIONALE -> R.string.gems_location_permission_rationale
            LocationPermissionGate.State.SETTINGS -> R.string.gems_location_permission_settings
            else -> R.string.gems_location_permission_required
        }
        notifySnackbar(context.getString(messageRes), isError = true)
    }

    fun checkLocationAndLoadGems(isRefresh: Boolean) {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) {
            requestPermission { granted ->
                if (granted) checkLocationAndLoadGems(isRefresh) else showLocationPermissionFeedback()
            }
            return
        }

        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null) {
                    notifySnackbar(context.getString(R.string.gems_location_failed), isError = true)
                    return@addOnSuccessListener
                }
                coroutineScope.launch {
                    val searchCity = try {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val address = withContext(Dispatchers.IO) {
                            @Suppress("DEPRECATION")
                            geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                        }
                        GemsCityResolver.resolveSearchCity(address)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "this area"
                    }
                    viewModel.loadGems(location.latitude, location.longitude, searchCity)
                }
            }
            .addOnFailureListener {
                notifySnackbar(context.getString(R.string.gems_location_failed), isError = true)
            }
    }

    fun requestGemsLoad(isUserInitiated: Boolean = false) {
        when (val state = resolvePermissionState()) {
            LocationPermissionGate.State.GRANTED -> checkLocationAndLoadGems(isRefresh = isUserInitiated)
            LocationPermissionGate.State.REQUEST,
            LocationPermissionGate.State.RATIONALE -> {
                if (state == LocationPermissionGate.State.RATIONALE) {
                    notifySnackbar(context.getString(R.string.gems_location_permission_rationale), isError = true)
                }
                requestPermission { granted ->
                    if (granted) checkLocationAndLoadGems(isRefresh = isUserInitiated) else showLocationPermissionFeedback()
                }
            }
            LocationPermissionGate.State.SETTINGS -> {
                notifySnackbar(context.getString(R.string.gems_location_permission_settings), isError = true)
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        var locationCallback: LocationCallback? = null

        fun start() {
            val hasFineLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFineLocation && !hasCoarseLocation) return

            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                GEMS_LOCATION_UPDATE_INTERVAL_MS
            ).build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    viewModel.updateCurrentLocation(location.latitude, location.longitude)
                }
            }
            locationCallback = callback
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
            } catch (_: SecurityException) {
                locationCallback = null
            }
        }

        fun stop() {
            val callback = locationCallback ?: return
            LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(callback)
            locationCallback = null
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            stop()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val message by viewModel.message.observeAsState()
    LaunchedEffect(message) {
        val text = message?.asString(context).orEmpty()
        if (text.isBlank()) return@LaunchedEffect
        notifySnackbar(text, isError = true)
        viewModel.clearMessage()
    }

    val discoverEvent by viewModel.discoverEvent.observeAsState()
    LaunchedEffect(discoverEvent) {
        val event = discoverEvent
        when (event) {
            is GemsViewModel.DiscoverEvent.Discovered -> {
                val reward = context.getString(R.string.gems_discovered_reward, event.rewardHoney)
                val text = if (event.firstGem) {
                    "$reward ${context.getString(R.string.gems_gem_finder_unlocked)}"
                } else {
                    reward
                }
                notifySnackbar(text, isError = false)
            }
            GemsViewModel.DiscoverEvent.AlreadyDiscovered ->
                notifySnackbar(context.getString(R.string.gems_already_discovered), isError = false)
            GemsViewModel.DiscoverEvent.TooFar ->
                notifySnackbar(context.getString(R.string.gems_discover_too_far), isError = true)
            GemsViewModel.DiscoverEvent.Failed ->
                notifySnackbar(context.getString(R.string.gems_discover_failed), isError = true)
            null -> Unit
        }
        if (event != null) viewModel.clearDiscoverEvent()
    }

    LaunchedEffect(Unit) {
        if (GemsLoadGate.shouldAutoLoad(viewModel.gemsState.value)) {
            requestGemsLoad()
        }
    }

    GemsScreen(
        viewModel = viewModel,
        onRetry = { requestGemsLoad(isUserInitiated = true) },
        onGemClick = { gem -> openInMaps(context, gem) },
        onDiscover = { gem -> viewModel.discoverGem(gem) }
    )
}

private fun openInMaps(context: android.content.Context, gem: Gem) {
    val geoUri = "geo:${gem.lat},${gem.lng}?q=${Uri.encode(gem.name)}".toUri()
    val mapsIntent = Intent(Intent.ACTION_VIEW, geoUri).setPackage("com.google.android.apps.maps")
    try {
        context.startActivity(mapsIntent)
    } catch (_: ActivityNotFoundException) {
        val webUrl = "https://www.google.com/maps/search/?api=1&query=${gem.lat},${gem.lng}"
        context.startActivity(Intent(Intent.ACTION_VIEW, webUrl.toUri()))
    }
}
