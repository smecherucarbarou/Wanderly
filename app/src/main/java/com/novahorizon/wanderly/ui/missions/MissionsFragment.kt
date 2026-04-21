package com.novahorizon.wanderly.ui.missions

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.derivedHiveRank
import com.novahorizon.wanderly.databinding.FragmentMissionsBinding
import com.novahorizon.wanderly.ui.common.WanderlyViewModelFactory
import com.novahorizon.wanderly.ui.common.showSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MissionsFragment : Fragment() {

    private var _binding: FragmentMissionsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MissionsViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyGraph.repository(requireContext()))
    }

    private var tempImageUri: Uri? = null
    private var tempImageFile: File? = null
    private var locationLookupInFlight = false
    private val logTag = "MissionsFragment"

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            showSnackbar(getString(R.string.mission_camera_permission_required), isError = true)
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkLocationAndGenerate()
        } else {
            showSnackbar(getString(R.string.mission_location_permission_required), isError = true)
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val imageUri = tempImageUri
            if (imageUri == null) {
                showSnackbar(getString(R.string.mission_photo_missing), isError = true)
                return@registerForActivityResult
            }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        requireContext().contentResolver.openInputStream(imageUri)?.use { input ->
                            android.graphics.BitmapFactory.decodeStream(input)
                        }
                    }
                    if (bitmap == null) {
                        showSnackbar(getString(R.string.photo_read_failed), isError = true)
                        return@launch
                    }
                    viewModel.verifyPhoto(bitmap)
                } catch (_: Exception) {
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
        _binding = FragmentMissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()
        viewModel.loadProfile()

        binding.newFlightButton.setOnClickListener { checkLocationAndGenerate() }
        binding.verifyButton.setOnClickListener { checkCameraPermissionAndStart() }
        binding.completeButton.setOnClickListener { viewModel.completeMission() }
        binding.learnMoreButton.setOnClickListener { viewModel.getPlaceDetails() }
    }

    private fun setupObservers() {
        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            profile?.let { updateUI(it) }
        }

        viewModel.streakMessage.observe(viewLifecycleOwner) { message ->
            message?.let {
                showSnackbar(it, isError = false)
            }
        }

        viewModel.missionState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is MissionsViewModel.MissionState.Generating -> {
                    binding.loadingIndicator.visibility = View.VISIBLE
                    binding.missionText.visibility = View.VISIBLE
                    binding.missionText.text = getString(R.string.mission_loading_state)
                    binding.newFlightButton.isEnabled = false
                    binding.buzzyBubble.text = getString(R.string.mission_bubble_generating)
                }

                is MissionsViewModel.MissionState.MissionReceived -> {
                    locationLookupInFlight = false
                    binding.missionText.text = state.text
                    binding.missionText.visibility = View.VISIBLE
                    binding.loadingIndicator.visibility = View.GONE
                    binding.newFlightButton.visibility = View.GONE
                    binding.verifyButton.visibility = View.VISIBLE
                    binding.verifyButton.isEnabled = true
                    binding.verifyButton.text = getString(R.string.mission_verify_location)
                    binding.completeButton.visibility = View.GONE
                    binding.learnMoreButton.visibility = View.GONE
                    binding.buzzyBubble.text = getString(R.string.mission_bubble_received)
                }

                is MissionsViewModel.MissionState.Verifying -> {
                    binding.verifyButton.isEnabled = false
                    binding.verifyButton.text = getString(R.string.mission_verifying_state)
                    binding.buzzyBubble.text = getString(R.string.mission_bubble_verifying)
                }

                is MissionsViewModel.MissionState.VerificationResult -> {
                    if (state.success) {
                        binding.verifyButton.visibility = View.GONE
                        binding.completeButton.visibility = View.VISIBLE
                        binding.learnMoreButton.visibility = View.VISIBLE
                        binding.buzzyBubble.text = getString(R.string.mission_bubble_verified)
                    } else {
                        binding.verifyButton.isEnabled = true
                        binding.verifyButton.text = getString(R.string.mission_take_photo_to_verify)
                        binding.buzzyBubble.text = state.message
                    }
                }

                is MissionsViewModel.MissionState.FetchingDetails -> {
                    binding.learnMoreButton.isEnabled = false
                    binding.learnMoreButton.text = getString(R.string.mission_fetching_details)
                }

                is MissionsViewModel.MissionState.DetailsReceived -> {
                    binding.learnMoreButton.visibility = View.GONE
                    binding.buzzyBubble.text = state.info
                }

                is MissionsViewModel.MissionState.Idle -> {
                    locationLookupInFlight = false
                    binding.verifyButton.visibility = View.GONE
                    binding.completeButton.visibility = View.GONE
                    binding.learnMoreButton.visibility = View.GONE
                    binding.newFlightButton.visibility = View.VISIBLE
                    binding.newFlightButton.isEnabled = true
                    binding.newFlightButton.text = getString(R.string.generate_mission)
                    binding.buzzyBubble.text = getString(R.string.mission_bubble_idle)
                    binding.missionText.text = getString(R.string.mission_empty_state)
                    binding.missionText.visibility = View.VISIBLE
                }

                is MissionsViewModel.MissionState.Error -> {
                    locationLookupInFlight = false
                    binding.loadingIndicator.visibility = View.GONE
                    binding.missionText.visibility = View.VISIBLE
                    binding.missionText.text = state.message
                    binding.newFlightButton.visibility = View.VISIBLE
                    binding.newFlightButton.isEnabled = true
                    binding.newFlightButton.text = getString(R.string.mission_retry)
                    binding.verifyButton.visibility = View.GONE
                    binding.completeButton.visibility = View.GONE
                    binding.learnMoreButton.isEnabled = true
                    binding.learnMoreButton.text = getString(R.string.mission_learn_more)
                    binding.buzzyBubble.text = state.message
                    showSnackbar(state.message, isError = true)
                }

                else -> Unit
            }
        }
    }

    private fun checkLocationAndGenerate() {
        if (locationLookupInFlight) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        locationLookupInFlight = true
        binding.newFlightButton.isEnabled = false
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.buzzyBubble.text = getString(R.string.mission_bubble_locating)
        binding.missionText.visibility = View.VISIBLE
        binding.missionText.text = getString(R.string.mission_location_loading)

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
            if (location != null) {
                val lifecycleOwner = viewLifecycleOwner
                lifecycleOwner.lifecycleScope.launch {
                    try {
                        val geocoder = Geocoder(requireContext(), Locale.getDefault())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                                if (!isAdded || _binding == null || lifecycleOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
                                    return@getFromLocation
                                }
                                val cityName = addresses.firstOrNull()?.locality ?: addresses.firstOrNull()?.adminArea
                                logDebug("City from geocoder: $cityName")
                                lifecycleOwner.lifecycleScope.launch {
                                    viewModel.generateMission(location.latitude, location.longitude, cityName)
                                }
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val addresses = withContext(Dispatchers.IO) {
                                geocoder.getFromLocation(location.latitude, location.longitude, 1)
                            }
                            val cityName = addresses?.firstOrNull()?.locality ?: addresses?.firstOrNull()?.adminArea
                            logDebug("City from geocoder: $cityName")
                            viewModel.generateMission(location.latitude, location.longitude, cityName)
                        }
                    } catch (e: Exception) {
                        logError("Error getting city name", e)
                        viewModel.generateMission(location.latitude, location.longitude, null)
                    }
                }
            } else {
                locationLookupInFlight = false
                binding.loadingIndicator.visibility = View.GONE
                binding.newFlightButton.isEnabled = true
                binding.buzzyBubble.text = getString(R.string.mission_location_failed)
                showSnackbar(getString(R.string.mission_location_failed), isError = true)
            }
        }.addOnFailureListener { error ->
            logError("Error getting precise location", error)
            locationLookupInFlight = false
            binding.loadingIndicator.visibility = View.GONE
            binding.newFlightButton.isEnabled = true
            binding.buzzyBubble.text = getString(R.string.mission_location_failed)
            showSnackbar(getString(R.string.mission_location_failed), isError = true)
        }
    }

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        try {
            tempImageFile?.delete()
            val tempFile = File.createTempFile("mission_verify_", ".jpg", requireContext().cacheDir)
            tempImageFile = tempFile
            tempImageUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", tempFile)
            takePhotoLauncher.launch(tempImageUri)
        } catch (_: Exception) {
            showSnackbar(getString(R.string.mission_camera_start_failed), isError = true)
        }
    }

    private fun updateUI(profile: Profile) {
        val honey = profile.honey ?: 0
        val derivedRank = profile.derivedHiveRank()

        binding.honeyCount.text = getString(R.string.mission_honey_format, honey)
        binding.rankName.text = getRankName(derivedRank)

        val maxHoney = HiveRank.maxHoneyForRank(derivedRank)
        val minHoney = HiveRank.minHoneyForRank(derivedRank)
        val progress = if (maxHoney > minHoney) {
            (((honey - minHoney).toFloat() / (maxHoney - minHoney)) * 100).toInt()
        } else {
            100
        }

        binding.honeyProgress.progress = progress
    }

    private fun getRankName(rank: Int) = when (rank) {
        1 -> getString(R.string.rank_1)
        2 -> getString(R.string.rank_2)
        3 -> getString(R.string.rank_3)
        else -> getString(R.string.rank_4)
    }

    override fun onDestroyView() {
        tempImageFile?.delete()
        tempImageFile = null
        tempImageUri = null
        super.onDestroyView()
        _binding = null
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(logTag, message)
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.e(logTag, message, throwable)
            } else {
                Log.e(logTag, message)
            }
        }
    }
}
