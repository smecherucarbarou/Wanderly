package com.novahorizon.wanderly.ui.gems

import com.novahorizon.wanderly.observability.AppLogger

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Gem
import com.novahorizon.wanderly.ui.common.LocationPermissionController
import com.novahorizon.wanderly.ui.common.LocationPermissionGate
import com.novahorizon.wanderly.ui.common.UiText
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.ui.compose.screens.map.GemsScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import dagger.hilt.android.AndroidEntryPoint
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class GemsFragment : Fragment() {
    private val logTag = "GemsFragment"

    private val viewModel: GemsViewModel by viewModels()
    private val locationPermissionController = LocationPermissionController(this)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        logDebug("onCreateView called")
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WanderlyTheme {
                    GemsScreen(
                        viewModel = viewModel,
                        onRetry = { requestGemsLoad(isUserInitiated = true) },
                        onGemClick = { gem -> openInMaps(gem) }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.message.observe(viewLifecycleOwner) { message ->
            val text = message?.asString(requireContext()).orEmpty()
            if (text.isBlank()) return@observe
            showSnackbar(text, isError = true)
            viewModel.clearMessage()
        }

        if (GemsLoadGate.shouldAutoLoad(viewModel.gemsState.value)) {
            requestGemsLoad()
        }
    }

    private fun requestGemsLoad(isUserInitiated: Boolean = false) {
        if (!isAdded) return

        when (val permissionState = locationPermissionController.resolveState()) {
            LocationPermissionGate.State.GRANTED -> checkLocationAndLoadGems(isRefresh = isUserInitiated)
            LocationPermissionGate.State.REQUEST,
            LocationPermissionGate.State.RATIONALE -> {
                if (permissionState == LocationPermissionGate.State.RATIONALE) {
                    showSnackbar(getString(R.string.gems_location_permission_rationale), isError = true)
                }
                locationPermissionController.requestPermission { granted ->
                    if (!isAdded) return@requestPermission
                    if (granted) {
                        checkLocationAndLoadGems(isRefresh = isUserInitiated)
                    } else {
                        showLocationPermissionFeedback()
                    }
                }
            }

            LocationPermissionGate.State.SETTINGS -> {
                showSnackbar(getString(R.string.gems_location_permission_settings), isError = true)
                locationPermissionController.openAppSettings()
            }
        }
    }

    private fun checkLocationAndLoadGems(isRefresh: Boolean = false) {
        logDebug("checkLocationAndLoadGems called, isRefresh=$isRefresh")
        val fragmentContext = context ?: return
        val fragmentActivity = activity ?: return

        val hasFineLocation = ContextCompat.checkSelfPermission(
            fragmentContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            fragmentContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) {
            locationPermissionController.requestPermission { granted ->
                if (!isAdded) return@requestPermission
                if (granted) {
                    checkLocationAndLoadGems(isRefresh)
                } else {
                    showLocationPermissionFeedback()
                }
            }
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(fragmentActivity)
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null) {
                    if (isAdded) {
                        showSnackbar(getString(R.string.gems_location_failed), isError = true)
                    }
                    return@addOnSuccessListener
                }

                val lifecycleOwner = viewLifecycleOwnerLiveData.value
                val hasActiveLifecycleOwner =
                    lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.INITIALIZED) == true
                if (!GemsLocationCallbackGuard.shouldHandleLocationSuccess(
                        hasLocation = true,
                        isFragmentAdded = isAdded,
                        hasBinding = true,
                        hasLifecycleOwner = hasActiveLifecycleOwner
                    )
                ) {
                    return@addOnSuccessListener
                }

                lifecycleOwner ?: return@addOnSuccessListener
                val callbackContext = context ?: return@addOnSuccessListener
                lifecycleOwner.lifecycleScope.launch {
                    val searchCity = try {
                        val geocoder = Geocoder(callbackContext, Locale.getDefault())
                        val address = withContext(Dispatchers.IO) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                            } else {
                                @Suppress("DEPRECATION")
                                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                            }
                        }

                        logDebug(
                            "Geocoder details: locality=${address?.locality}, subLocality=${address?.subLocality}, subAdmin=${address?.subAdminArea}, admin=${address?.adminArea}, feature=${address?.featureName}"
                        )

                        resolveSearchCity(address)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        "this area"
                    }

                    if (!isAdded) return@launch
                    viewModel.loadGems(location.latitude, location.longitude, searchCity)
                }
            }
            .addOnFailureListener {
                if (isAdded) {
                    showSnackbar(getString(R.string.gems_location_failed), isError = true)
                }
            }
    }

    private fun showLocationPermissionFeedback() {
        if (!isAdded) return
        val messageRes = when (locationPermissionController.resolveState()) {
            LocationPermissionGate.State.RATIONALE -> R.string.gems_location_permission_rationale
            LocationPermissionGate.State.SETTINGS -> R.string.gems_location_permission_settings
            else -> R.string.gems_location_permission_required
        }
        showSnackbar(getString(messageRes), isError = true)
    }

    private fun resolveSearchCity(address: Address?): String {
        if (address == null) return "this area"

        val locality = address.locality.cleanedPlaceLabel()
        if (locality != null) return locality

        val subAdmin = address.subAdminArea.cleanedPlaceLabel()
        val admin = address.adminArea.cleanedPlaceLabel()
        val subLocality = address.subLocality.cleanedPlaceLabel()
        val featureName = address.featureName.cleanedPlaceLabel()

        val nonCountyFallback = listOfNotNull(subLocality, featureName, subAdmin).firstOrNull { candidate ->
            !candidate.looksLikeCounty() && !candidate.equals(admin, ignoreCase = true)
        }
        if (nonCountyFallback != null) return nonCountyFallback

        if (subAdmin != null && !subAdmin.equals(admin, ignoreCase = true)) {
            return subAdmin
        }

        return admin ?: "this area"
    }

    private fun String?.cleanedPlaceLabel(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val collapsed = value.replace(Regex("\\s+"), " ").trim()
        val normalized = Normalizer.normalize(collapsed, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)

        val prefixes = listOf("municipiul ", "municipiu ", "orasul ", "oras ", "judetul ", "judet ", "comuna ")
        val matchedPrefix = prefixes.firstOrNull { normalized.startsWith(it) } ?: return collapsed.trim(',', '.', '-', ' ')
        return collapsed.drop(matchedPrefix.length).trim(',', '.', '-', ' ').ifBlank { null }
    }

    private fun String.looksLikeCounty(): Boolean {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
        return normalized.startsWith("judet") || normalized.endsWith(" county")
    }

    private fun openInMaps(gem: Gem) {
        if (!isAdded) return
        val geoUri = "geo:${gem.lat},${gem.lng}?q=${Uri.encode(gem.name)}".toUri()
        val mapsIntent = Intent(Intent.ACTION_VIEW, geoUri).setPackage("com.google.android.apps.maps")
        try {
            startActivity(mapsIntent)
        } catch (_: ActivityNotFoundException) {
            val webUrl = "https://www.google.com/maps/search/?api=1&query=${gem.lat},${gem.lng}"
            startActivity(Intent(Intent.ACTION_VIEW, webUrl.toUri()))
        }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d(logTag, message)
        }
    }
}
