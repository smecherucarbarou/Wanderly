package com.novahorizon.wanderly.ui.missions

import com.novahorizon.wanderly.observability.AppLogger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.MainActivity
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.ui.common.LocationPermissionController
import com.novahorizon.wanderly.ui.common.LocationPermissionGate
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.ui.compose.screens.missions.MissionsScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@AndroidEntryPoint
class MissionsFragment : Fragment() {

    private val viewModel: MissionsViewModel by viewModels()

    private var tempImageUri: Uri? = null
    private var tempImageFile: File? = null
    private var locationLookupInFlight = false
    private val logTag = "MissionsFragment"
    private val locationPermissionController = LocationPermissionController(this)

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isAdded) return@registerForActivityResult
        if (isGranted) {
            startCamera()
        } else {
            showCameraPermissionFeedback()
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (!isAdded) return@registerForActivityResult
        if (success) {
            val imageUri = tempImageUri
            if (imageUri == null) {
                showSnackbar(getString(R.string.mission_photo_missing), isError = true)
                return@registerForActivityResult
            }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        val resolver = requireContext().contentResolver
                        MissionPhotoDecoder.decodeForVerification {
                            resolver.openInputStream(imageUri)
                        }
                    }
                    if (bitmap == null) {
                        if (!isAdded) return@launch
                        showSnackbar(getString(R.string.photo_read_failed), isError = true)
                        return@launch
                    }
                    if (!isAdded) return@launch
                    viewModel.verifyPhoto(bitmap)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (!isAdded) return@launch
                    showSnackbar(getString(R.string.mission_photo_process_failed), isError = true)
                }
            }
        } else {
            showSnackbar(getString(R.string.mission_photo_missing), isError = true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WanderlyTheme {
                    MissionsScreen(
                        viewModel = viewModel,
                        onGenerateMission = { checkLocationAndGenerate() },
                        onVerifyPhoto = { checkCameraPermissionAndStart() },
                        onCompleteMission = { viewModel.completeMission() },
                        onLearnMore = { viewModel.fetchPlaceDetails() }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.loadProfile()

        viewModel.streakMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                showSnackbar(it, isError = false)
                (activity as? MainActivity)?.requestNotificationPermissionIfNeeded()
            }
        }
    }

    private fun checkLocationAndGenerate() {
        if (locationLookupInFlight) return
        when (val permissionState = locationPermissionController.resolveState()) {
            LocationPermissionGate.State.GRANTED -> Unit
            LocationPermissionGate.State.REQUEST,
            LocationPermissionGate.State.RATIONALE -> {
                if (permissionState == LocationPermissionGate.State.RATIONALE) {
                    showSnackbar(getString(R.string.mission_location_permission_rationale), isError = true)
                }
                locationPermissionController.requestPermission { granted ->
                    if (!isAdded) return@requestPermission
                    if (granted) {
                        checkLocationAndGenerate()
                    } else {
                        showLocationPermissionFeedback()
                    }
                }
                return
            }

            LocationPermissionGate.State.SETTINGS -> {
                showSnackbar(getString(R.string.mission_location_permission_settings), isError = true)
                locationPermissionController.openAppSettings()
                return
            }
        }
        locationLookupInFlight = true

        WanderlyGraph.missionLocationProvider().requestCurrentLocation(
            fragment = this,
            onSuccess = onSuccess@{ location ->
            if (!isAdded) return@onSuccess
            if (location != null) {
                val lifecycleOwner = viewLifecycleOwner
                lifecycleOwner.lifecycleScope.launch {
                    try {
                        val cityName = WanderlyGraph.missionCityResolver()
                            .resolveCityName(requireContext(), location)
                        if (!isAdded || lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                            return@launch
                        }
                        logDebug("City from geocoder: $cityName")
                        viewModel.generateMission(location.latitude, location.longitude, cityName)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logError("Error getting city name", e)
                        viewModel.generateMission(location.latitude, location.longitude, null)
                    }
                }
            } else {
                locationLookupInFlight = false
                showSnackbar(getString(R.string.mission_location_failed), isError = true)
            }
        },
            onFailure = onFailure@{ error ->
            if (!isAdded) return@onFailure
            logError("Error getting precise location", error)
            locationLookupInFlight = false
            showSnackbar(getString(R.string.mission_location_failed), isError = true)
        }
        )
    }

    private fun showLocationPermissionFeedback() {
        val messageRes = when (locationPermissionController.resolveState()) {
            LocationPermissionGate.State.RATIONALE -> R.string.mission_location_permission_rationale
            LocationPermissionGate.State.SETTINGS -> R.string.mission_location_permission_settings
            else -> R.string.mission_location_permission_required
        }
        showSnackbar(getString(messageRes), isError = true)
    }

    private fun checkCameraPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startCamera()

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showSnackbar(getString(R.string.mission_camera_permission_rationale), isError = true)
                markCameraPermissionRequested()
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }

            hasRequestedCameraPermission() -> {
                showSnackbar(getString(R.string.mission_camera_permission_settings), isError = true)
                locationPermissionController.openAppSettings()
            }

            else -> {
                markCameraPermissionRequested()
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun showCameraPermissionFeedback() {
        when {
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showSnackbar(getString(R.string.mission_camera_permission_rationale), isError = true)
            }

            hasRequestedCameraPermission() -> {
                showSnackbar(getString(R.string.mission_camera_permission_settings), isError = true)
                locationPermissionController.openAppSettings()
            }

            else -> showSnackbar(getString(R.string.mission_camera_permission_required), isError = true)
        }
    }

    private fun hasRequestedCameraPermission(): Boolean {
        return requireContext()
            .getSharedPreferences(CAMERA_PERMISSION_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CAMERA_PERMISSION_REQUESTED, false)
    }

    private fun markCameraPermissionRequested() {
        requireContext()
            .getSharedPreferences(CAMERA_PERMISSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CAMERA_PERMISSION_REQUESTED, true)
            .apply()
    }

    private fun startCamera() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = requireContext()
                val tempFile = withContext(Dispatchers.IO) {
                    tempImageFile?.delete()
                    val imagesDir = File(context.cacheDir, "images").apply {
                        mkdirs()
                    }
                    File.createTempFile("mission_verify_", ".jpg", imagesDir)
                }
                tempImageFile = tempFile
                tempImageUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
                takePhotoLauncher.launch(tempImageUri)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showSnackbar(getString(R.string.mission_camera_start_failed), isError = true)
            }
        }
    }

    override fun onDestroyView() {
        val fileToDelete = tempImageFile
        if (fileToDelete != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                fileToDelete.delete()
            }
        }
        tempImageFile = null
        tempImageUri = null
        super.onDestroyView()
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d(logTag, message)
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                AppLogger.e(logTag, message, throwable)
            } else {
                AppLogger.e(logTag, message)
            }
        }
    }

    private companion object {
        private const val CAMERA_PERMISSION_PREFS = "mission_camera_permission"
        private const val KEY_CAMERA_PERMISSION_REQUESTED = "camera_permission_requested"
    }
}
