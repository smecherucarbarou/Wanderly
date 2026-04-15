package com.novahorizon.wanderly.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Bundle
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
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.databinding.FragmentMapBinding
import com.novahorizon.wanderly.showSnackbar
import com.novahorizon.wanderly.ui.SocialViewModel
import com.novahorizon.wanderly.ui.WanderlyViewModelFactory
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: SocialViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyRepository(requireContext()))
    }
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val friendMarkers = mutableListOf<Marker>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupLocation()
        } else {
            showSnackbar("Location permission denied", isError = true)
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

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            setupLocation()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        binding.fabMyLocation.setOnClickListener {
            myLocationOverlay?.myLocation?.let { location ->
                binding.mapView.controller.animateTo(location)
            }
        }

        checkActiveMission()
        
        binding.mapLoading.visibility = View.VISIBLE
        binding.mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean = false
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                _binding?.mapLoading?.visibility = View.GONE
                return false
            }
        })
        // Hide after 3s regardless
        lifecycleScope.launch {
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
                        
                        // Resize Buzzy Icon to 60x60 safely (handling VectorDrawables)
                        val buzzyDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_buzzy)
                        if (buzzyDrawable != null) {
                            val bitmap = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            buzzyDrawable.setBounds(0, 0, canvas.width, canvas.height)
                            buzzyDrawable.draw(canvas)
                            icon = BitmapDrawable(resources, bitmap)
                        }

                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    friendMarkers.add(marker)
                    _binding?.mapView?.overlays?.add(marker)
                }
            }
            _binding?.mapView?.invalidate()
        }
    }

    private fun checkActiveMission() {
        val repository = WanderlyRepository(requireContext())
        val targetCoordinates = repository.getMissionTargetCoordinates()
        val missionText = repository.getMissionText()

        if (targetCoordinates != null) {
            binding.missionPreviewText.text = missionText ?: "Destination set!"
            binding.newFlightButton.text = "Go to Missions"

            binding.newFlightButton.setOnClickListener {
                findNavController().navigate(R.id.action_map_to_missions)
            }

            val targetPoint = GeoPoint(targetCoordinates.first, targetCoordinates.second)
            binding.mapView.controller.animateTo(targetPoint)
            binding.mapView.invalidate()
            return
        }
        
        binding.missionPreviewText.text = "Ready for a new adventure?"
        binding.newFlightButton.text = "Generate Mission"
        binding.newFlightButton.setOnClickListener {
            findNavController().navigate(R.id.action_map_to_missions)
        }
    }

    private fun setupLocation() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val provider = GpsMyLocationProvider(requireContext())
            myLocationOverlay = MyLocationNewOverlay(provider, binding.mapView).apply {
                enableMyLocation()
                enableFollowLocation()
            }
            binding.mapView.overlays.add(myLocationOverlay)

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    val prefs = requireActivity().getSharedPreferences("WanderlyPrefs", Context.MODE_PRIVATE)
                    if (prefs.getString(Constants.KEY_MISSION_TARGET_LAT, null) == null) {
                        binding.mapView.controller.setCenter(geoPoint)
                    }
                    updateUserLocation(location.latitude, location.longitude)
                }
            }
        }
    }
    
    private fun updateUserLocation(lat: Double, lng: Double) {
        lifecycleScope.launch {
            try {
                val repo = WanderlyRepository(requireContext())
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
        super.onDestroyView()
        _binding = null
    }
}
