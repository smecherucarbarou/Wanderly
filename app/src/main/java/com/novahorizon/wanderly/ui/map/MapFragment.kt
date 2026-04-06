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
        Configuration.getInstance().load(requireContext(), requireActivity().getPreferences(Context.MODE_PRIVATE))
        Configuration.getInstance().cacheMapTileCount = 12
        Configuration.getInstance().cacheMapTileOvershoot = 12
        
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.mapView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
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
        
        // Observe friends to display on map
        viewModel.friends.observe(viewLifecycleOwner) { friends ->
            binding.mapView.overlays.removeAll(friendMarkers)
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
                    binding.mapView.overlays.add(marker)
                }
            }
            binding.mapView.invalidate()
        }
    }

    private fun checkActiveMission() {
        val prefs = requireActivity().getSharedPreferences("WanderlyPrefs", Context.MODE_PRIVATE)
        val targetLatStr = prefs.getString("mission_target_lat", null)
        val targetLngStr = prefs.getString("mission_target_lng", null)
        val missionText = prefs.getString("mission_text", null)

        if (targetLatStr != null && targetLngStr != null) {
            val targetLat = targetLatStr.toDoubleOrNull()
            val targetLng = targetLngStr.toDoubleOrNull()
            
            if (targetLat != null && targetLng != null) {
                binding.missionPreviewText.text = missionText ?: "Destination set!"
                binding.newFlightButton.text = "Go to Missions"
                
                binding.newFlightButton.setOnClickListener {
                    findNavController().navigate(R.id.missionsFragment)
                }

                val targetPoint = GeoPoint(targetLat, targetLng)
                binding.mapView.controller.animateTo(targetPoint)
                binding.mapView.invalidate()
                return
            }
        }
        
        binding.missionPreviewText.text = "Ready for a new adventure?"
        binding.newFlightButton.text = "Generate Mission"
        binding.newFlightButton.setOnClickListener {
            findNavController().navigate(R.id.missionsFragment)
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
                    if (prefs.getString("mission_target_lat", null) == null) {
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
                val profile = repo.getCurrentProfile()
                if (profile != null) {
                    repo.updateProfile(profile.copy(last_lat = lat, last_lng = lng))
                }
                viewModel.loadFriends()
            } catch (e: Exception) {
                Log.e("MapFragment", "Failed to update location", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
