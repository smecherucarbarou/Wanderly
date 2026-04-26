package com.novahorizon.wanderly.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.novahorizon.wanderly.data.Mission
import com.novahorizon.wanderly.databinding.FragmentMapBinding
import com.novahorizon.wanderly.ui.common.LocationPermissionController
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
    private val mapViewModel: MapViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyGraph.repository(requireContext()))
    }
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val friendMarkers = mutableListOf<Marker>()
    private var friendProfiles: List<com.novahorizon.wanderly.data.Profile> = emptyList()
    private var friendMarkerIcon: BitmapDrawable? = null
    private val friendClusterIcons = mutableMapOf<Int, BitmapDrawable>()
    private var mapListener: MapListener? = null
    private val locationPermissionController = LocationPermissionController(this)

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

        mapViewModel.activeMission.observe(viewLifecycleOwner) { mission ->
            renderActiveMission(mission)
        }
        checkActiveMission()
        
        binding.mapLoading.visibility = View.VISIBLE
        mapListener = object : MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                renderFriendMarkers()
                return false
            }

            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                _binding?.mapLoading?.visibility = View.GONE
                renderFriendMarkers()
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
            friendProfiles = friends.filter { it.last_lat != null && it.last_lng != null }
            renderFriendMarkers()
        }

        val permissionState = locationPermissionController.resolveState()
        if (permissionState == LocationPermissionGate.State.GRANTED) {
            setupLocation(centerOnLocation = true)
        } else if (permissionState == LocationPermissionGate.State.REQUEST) {
            locationPermissionController.requestPermission { granted ->
                _binding ?: return@requestPermission
                if (granted) {
                    setupLocation(centerOnLocation = true)
                } else {
                    showLocationPermissionFeedback()
                }
            }
        }
    }

    private fun checkActiveMission() {
        mapViewModel.loadActiveMission()
    }

    private fun renderActiveMission(mission: Mission?) {
        val currentBinding = _binding ?: return

        if (mission != null) {
            currentBinding.missionPreviewText.text =
                mission.text.ifBlank { getString(R.string.map_active_mission_ready) }
            currentBinding.newFlightButton.text = getString(R.string.map_go_to_missions)

            currentBinding.newFlightButton.setOnClickListener {
                findNavController().navigate(R.id.action_map_to_missions)
            }

            val targetPoint = GeoPoint(mission.location_lat, mission.location_lng)
            currentBinding.mapView.controller.animateTo(targetPoint)
            currentBinding.mapView.invalidate()
            return
        }

        currentBinding.missionPreviewText.text = getString(R.string.map_preview_default)
        currentBinding.newFlightButton.text = getString(R.string.generate_mission)
        currentBinding.newFlightButton.setOnClickListener {
            findNavController().navigate(R.id.action_map_to_missions)
        }
    }

    private fun setupLocation(centerOnLocation: Boolean) {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasFineLocation || hasCoarseLocation) {
            if (myLocationOverlay == null) {
                val provider = GpsMyLocationProvider(requireContext())
                myLocationOverlay = MyLocationNewOverlay(provider, binding.mapView).apply {
                    enableMyLocation()
                    enableFollowLocation()
                }
                binding.mapView.overlays.add(myLocationOverlay)
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                val currentBinding = _binding
                if (!MapLocationCallbackGuard.shouldHandleLocationUpdate(
                        hasLocation = location != null,
                        isFragmentAdded = isAdded,
                        hasBinding = currentBinding != null
                    )
                ) {
                    return@addOnSuccessListener
                }

                location ?: return@addOnSuccessListener
                currentBinding ?: return@addOnSuccessListener

                viewLifecycleOwner.lifecycleScope.launch {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    val hasMissionTarget = mapViewModel.activeMission.value != null
                    val b = _binding ?: return@launch
                    if (centerOnLocation || !hasMissionTarget) {
                        b.mapView.controller.setCenter(geoPoint)
                    }
                }
                updateUserLocation(location.latitude, location.longitude)
            }
        }
    }

    private fun handleMyLocationAction() {
        when (val permissionState = locationPermissionController.resolveState()) {
            LocationPermissionGate.State.GRANTED -> {
                myLocationOverlay?.myLocation?.let { location ->
                    binding.mapView.controller.animateTo(location)
                } ?: setupLocation(centerOnLocation = true)
            }

            LocationPermissionGate.State.REQUEST,
            LocationPermissionGate.State.RATIONALE -> {
                if (permissionState == LocationPermissionGate.State.RATIONALE) {
                    showSnackbar(getString(R.string.map_location_permission_rationale), isError = true)
                }
                locationPermissionController.requestPermission { granted ->
                    _binding ?: return@requestPermission
                    if (granted) {
                        setupLocation(centerOnLocation = true)
                    } else {
                        showLocationPermissionFeedback()
                    }
                }
            }

            LocationPermissionGate.State.SETTINGS -> {
                showSnackbar(getString(R.string.map_location_permission_settings), isError = true)
                locationPermissionController.openAppSettings()
            }
        }
    }

    private fun showLocationPermissionFeedback() {
        val messageRes = when (locationPermissionController.resolveState()) {
            LocationPermissionGate.State.RATIONALE -> R.string.map_location_permission_rationale
            LocationPermissionGate.State.SETTINGS -> R.string.map_location_permission_settings
            else -> R.string.map_location_permission_denied
        }
        showSnackbar(getString(messageRes), isError = true)
    }
    
    private fun updateUserLocation(lat: Double, lng: Double) {
        mapViewModel.updateUserLocation(lat, lng)
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

    private fun renderFriendMarkers() {
        val currentBinding = _binding ?: return
        friendMarkers.forEach { currentBinding.mapView.overlays.remove(it) }
        friendMarkers.clear()

        if (friendProfiles.isEmpty()) {
            currentBinding.mapView.invalidate()
            return
        }

        val profileById = friendProfiles.associateBy { it.id }
        val clusters = FriendMapClusterer.cluster(
            items = friendProfiles.map { friend ->
                FriendMapPoint(
                    id = friend.id,
                    latitude = friend.last_lat ?: 0.0,
                    longitude = friend.last_lng ?: 0.0
                )
            },
            zoomLevel = currentBinding.mapView.zoomLevelDouble,
            clusterRadiusPx = 120
        )

        clusters.forEach { cluster ->
            val marker = Marker(currentBinding.mapView).apply {
                position = GeoPoint(cluster.latitude, cluster.longitude)
                if (cluster.memberIds.size > 1) {
                    title = "${cluster.memberIds.size} friends"
                    icon = getFriendClusterIcon(cluster.memberIds.size)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                } else {
                    val profile = profileById[cluster.memberIds.first()]
                    title = profile?.username
                    icon = getFriendMarkerIcon()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
            }
            friendMarkers += marker
            currentBinding.mapView.overlays.add(marker)
        }

        currentBinding.mapView.invalidate()
    }

    private fun getFriendClusterIcon(memberCount: Int): BitmapDrawable {
        friendClusterIcons[memberCount]?.let { return it }

        val size = (72 * resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_gradient)?.let { background ->
            background.setBounds(0, 0, size, size)
            background.draw(canvas)
        }
        ContextCompat.getDrawable(requireContext(), R.drawable.ic_honeycomb)?.let { honeycomb ->
            val inset = size / 4
            honeycomb.alpha = 160
            honeycomb.setBounds(inset, inset, size - inset, size - inset)
            honeycomb.draw(canvas)
        }
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(requireContext(), R.color.text_primary)
            textAlign = Paint.Align.CENTER
            textSize = 20f * resources.displayMetrics.density
            isFakeBoldText = true
            val textY = size / 2f - (descent() + ascent()) / 2f
            canvas.drawText(memberCount.toString(), size / 2f, textY, this)
        }
        return BitmapDrawable(resources, bitmap).also { friendClusterIcons[memberCount] = it }
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
            friendMarkers.forEach(mapView.overlays::remove)
            myLocationOverlay?.disableFollowLocation()
            myLocationOverlay?.disableMyLocation()
            myLocationOverlay?.let { mapView.overlays.remove(it) }
            friendMarkers.clear()
            mapView.onPause()
            mapView.onDetach()
        }
        mapListener = null
        myLocationOverlay = null
        friendProfiles = emptyList()
        friendMarkerIcon = null
        friendClusterIcons.clear()
        super.onDestroyView()
        _binding = null
    }
}
