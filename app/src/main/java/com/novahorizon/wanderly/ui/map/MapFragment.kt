package com.novahorizon.wanderly.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.ui.common.LocationPermissionController
import com.novahorizon.wanderly.ui.common.LocationPermissionGate
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.ui.compose.screens.map.MapScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@AndroidEntryPoint
class MapFragment : Fragment() {

    private val mapViewModel: MapViewModel by viewModels()

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var mapView: MapView? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val friendMarkers = mutableListOf<Marker>()
    private var friendMarkerIcon: BitmapDrawable? = null
    private val friendClusterIcons = mutableMapOf<Int, BitmapDrawable>()
    private val locationPermissionController = LocationPermissionController(this)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WanderlyTheme {
                    val activeMission by mapViewModel.activeMission.asFlow().collectAsStateWithLifecycle(null)
                    val isMapReady by mapViewModel.isMapReady.asFlow().collectAsStateWithLifecycle(false)
                    MapScreen(
                        activeMission = activeMission,
                        isMapReady = isMapReady,
                        onMyLocation = { handleMyLocationAction() },
                        onNavigateToMissions = {
                            findNavController().navigate(R.id.action_map_to_missions)
                        },
                        onMapViewCreated = { map -> onMapReady(map) },
                        onMapViewDisposed = { cleanupMap() }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        mapViewModel.loadActiveMission()

        mapViewModel.clusters.observe(viewLifecycleOwner) {
            renderFriendMarkers(it)
        }
    }

    private fun onMapReady(map: MapView) {
        mapView = map
        mapViewModel.onMapReady()

        val savedPosition = mapViewModel.cameraPosition.value
        if (savedPosition != null) {
            map.controller.setCenter(GeoPoint(savedPosition.latitude, savedPosition.longitude))
            map.controller.setZoom(savedPosition.zoom)
        }

        requestLocationAfterMapReady()

        mapViewModel.activeMission.value?.let { mission ->
            map.controller.animateTo(GeoPoint(mission.location_lat, mission.location_lng))
        }
    }

    private fun requestLocationAfterMapReady() {
        val permissionState = locationPermissionController.resolveState()
        if (permissionState == LocationPermissionGate.State.GRANTED) {
            setupLocation(centerOnLocation = true)
        } else if (permissionState == LocationPermissionGate.State.REQUEST) {
            locationPermissionController.requestPermission { granted ->
                if (!isAdded) return@requestPermission
                if (granted) {
                    setupLocation(centerOnLocation = true)
                } else {
                    showLocationPermissionFeedback()
                }
            }
        }
    }

    private fun handleMyLocationAction() {
        when (val permissionState = locationPermissionController.resolveState()) {
            LocationPermissionGate.State.GRANTED -> {
                myLocationOverlay?.myLocation?.let { location ->
                    mapView?.controller?.animateTo(location)
                } ?: setupLocation(centerOnLocation = true)
            }

            LocationPermissionGate.State.REQUEST,
            LocationPermissionGate.State.RATIONALE -> {
                if (permissionState == LocationPermissionGate.State.RATIONALE) {
                    showSnackbar(getString(R.string.map_location_permission_rationale), isError = true)
                }
                locationPermissionController.requestPermission { granted ->
                    if (!isAdded) return@requestPermission
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

    private fun setupLocation(centerOnLocation: Boolean) {
        val map = mapView ?: return
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
                myLocationOverlay = MyLocationNewOverlay(provider, map).apply {
                    enableMyLocation()
                    enableFollowLocation()
                }
                map.overlays.add(myLocationOverlay)
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (!MapLocationCallbackGuard.shouldHandleLocationUpdate(
                        hasLocation = location != null,
                        isFragmentAdded = isAdded,
                        hasBinding = mapView != null
                    )
                ) {
                    return@addOnSuccessListener
                }

                location ?: return@addOnSuccessListener

                viewLifecycleOwner.lifecycleScope.launch {
                    val geoPoint = GeoPoint(location.latitude, location.longitude)
                    val hasMissionTarget = mapViewModel.activeMission.value != null
                    val activeMap = mapView ?: return@launch
                    if (centerOnLocation || !hasMissionTarget) {
                        activeMap.controller.setCenter(geoPoint)
                    }
                }
                mapViewModel.updateUserLocation(location.latitude, location.longitude)
            }
        }
    }

    private fun getFriendMarkerIcon(): BitmapDrawable? {
        friendMarkerIcon?.let { return it }
        val buzzyDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_buzzy) ?: return null
        val bitmap = createBitmap(60, 60)
        val canvas = Canvas(bitmap)
        buzzyDrawable.setBounds(0, 0, canvas.width, canvas.height)
        buzzyDrawable.draw(canvas)
        return bitmap.toDrawable(resources).also { friendMarkerIcon = it }
    }

    private fun renderFriendMarkers(clusters: List<FriendMapCluster>) {
        val map = mapView ?: return
        friendMarkers.forEach { map.overlays.remove(it) }
        friendMarkers.clear()

        if (clusters.isEmpty()) {
            map.invalidate()
            return
        }

        clusters.forEach { cluster ->
            val marker = Marker(map).apply {
                position = GeoPoint(cluster.latitude, cluster.longitude)
                if (cluster.memberIds.size > 1) {
                    title = "${cluster.memberIds.size} friends"
                    icon = getFriendClusterIcon(cluster.memberIds.size)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                } else {
                    title = mapViewModel.getFriendUsername(cluster.memberIds.first())
                    icon = getFriendMarkerIcon()
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
            }
            friendMarkers += marker
            map.overlays.add(marker)
        }

        map.invalidate()
    }

    private fun getFriendClusterIcon(memberCount: Int): BitmapDrawable {
        friendClusterIcons[memberCount]?.let { return it }
        val size = (72 * resources.displayMetrics.density).toInt()
        val bitmap = createBitmap(size, size)
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
        return bitmap.toDrawable(resources).also { friendClusterIcons[memberCount] = it }
    }

    private fun cleanupMap() {
        val currentMap = mapView
        if (currentMap != null) {
            mapViewModel.saveCameraPosition(
                currentMap.mapCenter.latitude,
                currentMap.mapCenter.longitude,
                currentMap.zoomLevelDouble
            )
            friendMarkers.forEach(currentMap.overlays::remove)
            myLocationOverlay?.disableFollowLocation()
            myLocationOverlay?.disableMyLocation()
            myLocationOverlay?.let { currentMap.overlays.remove(it) }
            friendMarkers.clear()
            currentMap.onPause()
            currentMap.onDetach()
        }
        mapView = null
        myLocationOverlay = null
        friendMarkerIcon?.bitmap?.recycle()
        friendMarkerIcon = null
        friendClusterIcons.values.forEach { it.bitmap.recycle() }
        friendClusterIcons.clear()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        mapViewModel.loadActiveMission()
        mapViewModel.loadFriends()
    }

    override fun onPause() {
        super.onPause()
        val map = mapView
        if (map != null) {
            mapViewModel.saveCameraPosition(
                map.mapCenter.latitude,
                map.mapCenter.longitude,
                map.zoomLevelDouble
            )
        }
        map?.onPause()
    }

    override fun onDestroyView() {
        cleanupMap()
        super.onDestroyView()
    }
}
