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
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.data.derivedHiveRank
import com.novahorizon.wanderly.databinding.FragmentMissionsBinding
import com.novahorizon.wanderly.showSnackbar
import com.novahorizon.wanderly.ui.MissionsViewModel
import com.novahorizon.wanderly.ui.WanderlyViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MissionsFragment : Fragment() {

    private var _binding: FragmentMissionsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MissionsViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyRepository(requireContext()))
    }

    private var tempImageUri: Uri? = null
    private var locationLookupInFlight = false

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            showSnackbar("Buzzy needs camera access to see your progress!", isError = true)
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && tempImageUri != null) {
            lifecycleScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        val input = requireContext().contentResolver.openInputStream(tempImageUri!!)
                        android.graphics.BitmapFactory.decodeStream(input)
                    }
                    viewModel.verifyPhoto(bitmap)
                } catch (_: Exception) {
                    showSnackbar("Failed to process photo", isError = true)
                }
            }
        } else {
            showSnackbar("Buzzy did not see a photo. Try again?", isError = true)
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
                    binding.missionText.visibility = View.GONE
                    binding.newFlightButton.isEnabled = false
                    binding.buzzyBubble.text = "Buzzy is scouting a real spot nearby..."
                }

                is MissionsViewModel.MissionState.MissionReceived -> {
                    locationLookupInFlight = false
                    binding.missionText.text = state.text
                    binding.missionText.visibility = View.VISIBLE
                    binding.loadingIndicator.visibility = View.GONE
                    binding.newFlightButton.visibility = View.GONE
                    binding.verifyButton.visibility = View.VISIBLE
                    binding.verifyButton.isEnabled = true
                    binding.verifyButton.text = "Verify Location"
                    binding.completeButton.visibility = View.GONE
                    binding.learnMoreButton.visibility = View.GONE
                    binding.buzzyBubble.text = "New mission! Use your bee-senses to find it!"
                }

                is MissionsViewModel.MissionState.Verifying -> {
                    binding.verifyButton.isEnabled = false
                    binding.verifyButton.text = "Buzzy is looking..."
                    binding.buzzyBubble.text = "Let me check the map and your photo..."
                }

                is MissionsViewModel.MissionState.VerificationResult -> {
                    if (state.success) {
                        binding.verifyButton.visibility = View.GONE
                        binding.completeButton.visibility = View.VISIBLE
                        binding.learnMoreButton.visibility = View.VISIBLE
                        binding.buzzyBubble.text = "Buzzing success!"
                    } else {
                        binding.verifyButton.isEnabled = true
                        binding.verifyButton.text = "Take Photo to Verify"
                        binding.buzzyBubble.text = state.message
                    }
                }

                is MissionsViewModel.MissionState.FetchingDetails -> {
                    binding.learnMoreButton.isEnabled = false
                    binding.learnMoreButton.text = "Buzzy is thinking..."
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
                    binding.buzzyBubble.text = "Good job! Ready for another?"
                }

                is MissionsViewModel.MissionState.Error -> {
                    locationLookupInFlight = false
                    binding.loadingIndicator.visibility = View.GONE
                    binding.newFlightButton.isEnabled = true
                    binding.learnMoreButton.isEnabled = true
                    binding.learnMoreButton.text = "Learn more about this place"
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
            showSnackbar("Buzzy needs your location to find flowers!", isError = true)
            return
        }
        locationLookupInFlight = true
        binding.newFlightButton.isEnabled = false
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.buzzyBubble.text = "Pinning your launch point..."

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
            if (location != null) {
                lifecycleScope.launch {
                    try {
                        val geocoder = Geocoder(requireContext(), Locale.getDefault())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                                val cityName = addresses.firstOrNull()?.locality ?: addresses.firstOrNull()?.adminArea
                                Log.d("MissionsFragment", "City from geocoder: $cityName")
                                viewModel.generateMission(location.latitude, location.longitude, cityName)
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            val addresses = withContext(Dispatchers.IO) {
                                geocoder.getFromLocation(location.latitude, location.longitude, 1)
                            }
                            val cityName = addresses?.firstOrNull()?.locality ?: addresses?.firstOrNull()?.adminArea
                            Log.d("MissionsFragment", "City from geocoder: $cityName")
                            viewModel.generateMission(location.latitude, location.longitude, cityName)
                        }
                    } catch (e: Exception) {
                        Log.e("MissionsFragment", "Error getting city name", e)
                        viewModel.generateMission(location.latitude, location.longitude, null)
                    }
                }
            } else {
                locationLookupInFlight = false
                binding.loadingIndicator.visibility = View.GONE
                binding.newFlightButton.isEnabled = true
                binding.buzzyBubble.text = "Could not get your launch point."
                showSnackbar("Could not get precise location", isError = true)
            }
        }.addOnFailureListener { error ->
            Log.e("MissionsFragment", "Error getting precise location", error)
            locationLookupInFlight = false
            binding.loadingIndicator.visibility = View.GONE
            binding.newFlightButton.isEnabled = true
            binding.buzzyBubble.text = "Could not get your launch point."
            showSnackbar("Could not get precise location", isError = true)
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
            val tempFile = File.createTempFile("mission_verify_", ".jpg", requireContext().cacheDir).apply {
                createNewFile()
                deleteOnExit()
            }
            tempImageUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", tempFile)
            takePhotoLauncher.launch(tempImageUri)
        } catch (_: Exception) {
            showSnackbar("Could not start camera interface", isError = true)
        }
    }

    private fun updateUI(profile: Profile) {
        val honey = profile.honey ?: 0
        val derivedRank = profile.derivedHiveRank()

        binding.honeyCount.text = "$honey Honey"
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
        super.onDestroyView()
        _binding = null
    }
}
