package com.novahorizon.wanderly.ui.map

import com.novahorizon.wanderly.observability.AppLogger

import android.location.Location
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.data.FriendLocation
import com.novahorizon.wanderly.data.Mission
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CameraPosition(
    val latitude: Double = 46.77,
    val longitude: Double = 23.59,
    val zoom: Double = 16.0
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repository: WanderlyRepository
) : ViewModel() {
    private val _activeMission = MutableLiveData<Mission?>()
    val activeMission: LiveData<Mission?> = _activeMission

    private val _isMapReady = MutableLiveData(false)
    val isMapReady: LiveData<Boolean> = _isMapReady

    private val _cameraPosition = MutableLiveData(CameraPosition())
    val cameraPosition: LiveData<CameraPosition> = _cameraPosition

    private val _clusters = MutableLiveData<List<FriendMapCluster>>(emptyList())
    val clusters: LiveData<List<FriendMapCluster>> = _clusters

    private var currentZoom: Double = 16.0
    private var friendLocations: List<FriendLocation> = emptyList()

    // Self location is tracked from the device only; it is never read back from `profiles`.
    private var lastDeviceLat: Double? = null
    private var lastDeviceLng: Double? = null

    fun onMapReady() {
        _isMapReady.value = true
    }

    fun saveCameraPosition(latitude: Double, longitude: Double, zoom: Double) {
        currentZoom = zoom
        _cameraPosition.value = CameraPosition(latitude, longitude, zoom)
        recomputeClusters()
    }

    fun loadFriends() {
        viewModelScope.launch {
            try {
                val friends = repository.getFriendLocations()
                friendLocations = friends.filter { it.last_lat != null && it.last_lng != null }
                recomputeClusters()
            } catch (e: Exception) {
                CrashReporter.recordNonFatal(
                    CrashEvent.MAP_LOCATION_UPDATE_FAILED,
                    e,
                    CrashKey.COMPONENT to "map",
                    CrashKey.OPERATION to "load_friends"
                )
            }
        }
    }

    private fun recomputeClusters() {
        if (friendLocations.isEmpty()) {
            _clusters.value = emptyList()
            return
        }
        val points = friendLocations.map { friend ->
            FriendMapPoint(
                id = friend.id,
                latitude = friend.last_lat ?: 0.0,
                longitude = friend.last_lng ?: 0.0
            )
        }
        _clusters.value = FriendMapClusterer.cluster(
            items = points,
            zoomLevel = currentZoom,
            clusterRadiusPx = 120
        )
    }

    fun getFriendUsername(friendId: String): String? {
        return friendLocations.find { it.id == friendId }?.username
    }

    fun loadActiveMission() {
        viewModelScope.launch {
            val targetCoordinates = repository.getMissionTargetCoordinates()
            if (targetCoordinates == null) {
                _activeMission.postValue(null)
                return@launch
            }

            _activeMission.postValue(
                Mission(
                    user_id = "",
                    text = repository.getMissionText().orEmpty(),
                    location_lat = targetCoordinates.first,
                    location_lng = targetCoordinates.second,
                    city = repository.getMissionCity().orEmpty()
                )
            )
        }
    }

    fun updateUserLocation(lat: Double, lng: Double) {
        viewModelScope.launch {
            try {
                val previousLat = lastDeviceLat
                val previousLng = lastDeviceLng

                val movedFarEnough = if (previousLat == null || previousLng == null) {
                    true
                } else {
                    val results = FloatArray(1)
                    Location.distanceBetween(previousLat, previousLng, lat, lng, results)
                    results[0] > 50.0
                }

                if (movedFarEnough) {
                    lastDeviceLat = lat
                    lastDeviceLng = lng
                    repository.updateProfileLocation(lat, lng)
                }
            } catch (e: Exception) {
                CrashReporter.recordNonFatal(
                    CrashEvent.MAP_LOCATION_UPDATE_FAILED,
                    e,
                    CrashKey.COMPONENT to "map",
                    CrashKey.OPERATION to "location_update"
                )
                if (BuildConfig.DEBUG) {
                    AppLogger.e("MapViewModel", "Failed to update location", e)
                } else {
                    AppLogger.e("MapViewModel", "Failed to update location")
                }
            }
        }
    }
}
