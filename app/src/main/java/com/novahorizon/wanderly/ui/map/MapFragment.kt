package com.novahorizon.wanderly.ui.map

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.databinding.FragmentMapBinding
import com.novahorizon.wanderly.ui.common.LocationPermissionGate
import com.novahorizon.wanderly.ui.common.WanderlyViewModelFactory
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.ui.social.SocialViewModel
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SocialViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyGraph.repository(requireContext()))
    }
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val friendMarkers = mutableListOf<Marker>()
    private var friendMarkerIcon: BitmapDrawable? = null
    private var mapListener: MapListener? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupLocation(centerOnLocation = true)
        } else {
            showLocationPermissionFeedback()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().load(
            requireContext(),
            requireActivity().getPreferences(Context.MODE_PRIVATE)
        )
        Configuration.getInstance().cacheMapTileCount = 9.toShort()
        Configuration.getInstance().cacheMapTileOvershoot = 9.toShort()
        Configuration.getInstance().osmdroidTileCache =
            requireContext().cacheDir.resolve("osmdroid")
        
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.mapView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        
        // Map restrictions to prevent infinite tiling and out-of-bounds scrolling
        binding.mapView.minZoomLevel = 3.0
        binding.mapView.isVerticalMapRepetitionEnabled = false
        binding.mapView.isHorizontalMapRepetitionEnabled = false
        binding.mapView.setScrollableAreaLimitLatitude(85.0, -85.0, 0)
        binding.mapView.setScrollableAreaLimitLongitude(-180.0, 180.0, 0)

        binding.mapView.controller.setZoom(16.0)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        binding.fabMyLocation.setOnClickListener {
            handleMyLocationAction()
        }

        checkActiveMission()
        
        binding.mapLoading.visibility = View.VISIBLE
        mapListener = object : MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean = false
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                _binding?.mapLoading?.visibility = View.GONE
                return false
            }
        }
        binding.mapView.addMapListener(mapListener)
        // Hide after 3s regardless
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(3000)
            _binding?.mapLoading?.visibility = View.GONE
        }
        
        // Observe friends to display on map
        viewModel.friends.observe(viewLifecycleOwner) { friends ->
            _binding?.mapView?.overlays?.removeAll(friendMarkers)
            friendMarkers.clear()
            
            friends.forEach { friend ->
                if (friend.last_lat != null && friend.last_lng != null) {
                    val marker = Marker(binding.mapView).apply {
                        position = GeoPoint(friend.last_lat, friend.last_lng)
                        title = friend.username
                        icon = getFriendMarkerIcon()

                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    friendMarkers.add(marker)
                    _binding?.mapView?.overlays?.add(marker)
                }
            }
            _binding?.mapView?.invalidate()
        }

        val permissionState = resolveLocationPermissionState()
        if (permissionState == LocationPermissionGate.State.GRANTED) {
            setupLocation(centerOnLocation = true)
        } else if (permissionState == LocationPermissionGate.State.REQUEST) {
            launchLocationPermissionRequest()
        }
    }

    private fun checkActiveMission() {
        val repository = WanderlyGraph.repository(requireContext())
        val targetCoordinates = repository.getMissionTargetCoordinates()
        val missionText = repository.getMissionText()

        if (targetCoordinates != null) {
            binding.missionPreviewText.text = missionText ?: getString(R.string.map_active_mission_ready)
            binding.newFlightButton.text = getString(R.string.map_go_to_missions)

            binding.newFlightButton.setOnClickListener {
                findNavController().navigate(R.id.action_map_to_missions)
            }

            val targetPoint = GeoPoint(targetCoordinates.first, targetCoordinates.second)
            binding.mapView.controller.animateTo(targetPoint)
            binding.mapView.invalidate()
            return
        }
        
        binding.missionPreviewText.text = getString(R.string.map_preview_default)
        binding.newFlightButton.text = getString(R.string.generate_mission)
        binding.newFlightButton.setOnClickListener {
            findNavController().navigate(R.id.action_map_to_missions)
        }
    }

    private fun setupLocation(centerOnLocation: Boolean) {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (myLocationOverlay == null) {
                val provider = GpsMyLocationProvider(requireContext())
                myLocationOverlay = MyLocationNewOverlay(provider, binding.mapView).apply {
                    enableMyLocation()
                    enableFollowLocation()
                }
                binding.mapView.overlays.add(myLocationOverlay)
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    if (centerOnLocation || !WanderlyGraph.repository(requireContext()).hasMissionTargetCoordinates()) {
                        binding.mapView.controller.setCenter(geoPoint)
                    }
                    updateUserLocation(location.latitude, location.longitude)
                }
            }
        }
    }

    private fun handleMyLocationAction() {
        when (resolveLocationPermissionState()) {
            LocationPermissionGate.State.GRANTED -> {
                myLocationOverlay?.myLocation?.let { location ->
                    binding.mapView.controller.animateTo(location)
                } ?: setupLocation(centerOnLocation = true)
            }

            LocationPermissionGate.State.REQUEST,
            LocationPermissionGate.State.RATIONALE -> {
                if (resolveLocationPermissionState() == LocationPermissionGate.State.RATIONALE) {
                    showSnackbar(getString(R.string.map_location_permission_rationale), isError = true)
                }
                launchLocationPermissionRequest()
            }

            LocationPermissionGate.State.SETTINGS -> {
                showSnackbar(getString(R.string.map_location_permission_settings), isError = true)
                openAppSettings()
            }
        }
    }

    private fun resolveLocationPermissionState(): LocationPermissionGate.State {
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return LocationPermissionGate.resolveState(
            hasPermission = hasPermission,
            hasRequestedBefore = LocationPermissionGate.hasRequestedBefore(requireContext()),
            shouldShowRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        )
    }

    private fun launchLocationPermissionRequest() {
        LocationPermissionGate.markRequestedBefore(requireContext())
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun showLocationPermissionFeedback() {
        val messageRes = when (resolveLocationPermissionState()) {
            LocationPermissionGate.State.RATIONALE -> R.string.map_location_permission_rationale
            LocationPermissionGate.State.SETTINGS -> R.string.map_location_permission_settings
            else -> R.string.map_location_permission_denied
        }
        showSnackbar(getString(messageRes), isError = true)
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", requireContext().packageName, null)
        )
        startActivity(intent)
    }
    
    private fun updateUserLocation(lat: Double, lng: Double) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val repo = WanderlyGraph.repository(requireContext())
                val profile = repo.getCurrentProfile() ?: return@launch
                
                // Only update if location changed significantly (more than 50 meters approx)
                val lastLat = profile.last_lat ?: 0.0
                val lastLng = profile.last_lng ?: 0.0
                
                val results = FloatArray(1)
                Location.distanceBetween(lastLat, lastLng, lat, lng, results)
                
                if (results[0] > 50.0 || profile.last_lat == null) {
                    repo.updateProfile(profile.copy(last_lat = lat, last_lng = lng))
                }
            } catch (e: Exception) {
                Log.e("MapFragment", "Failed to update location", e)
            }
        }
    }

    private fun getFriendMarkerIcon(): BitmapDrawable? {
        friendMarkerIcon?.let { return it }

        val buzzyDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_buzzy) ?: return null
        val bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        buzzyDrawable.setBounds(0, 0, canvas.width, canvas.height)
        buzzyDrawable.draw(canvas)

        return BitmapDrawable(resources, bitmap).also { friendMarkerIcon = it }
    }

    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        binding.mapView.onResume()
        checkActiveMission()
        viewModel.loadFriends()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        val mapView = _binding?.mapView
        if (mapView != null) {
            mapListener?.let { mapView.removeMapListener(it) }
            mapView.overlays.removeAll(friendMarkers)
            myLocationOverlay?.disableFollowLocation()
            myLocationOverlay?.disableMyLocation()
            myLocationOverlay?.let { mapView.overlays.remove(it) }
            friendMarkers.clear()
            mapView.onPause()
            mapView.onDetach()
        }
        mapListener = null
        myLocationOverlay = null
        friendMarkerIcon = null
        super.onDestroyView()
        _binding = null
    }
}
