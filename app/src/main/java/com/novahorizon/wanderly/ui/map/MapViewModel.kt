package com.novahorizon.wanderly.ui.map

import com.novahorizon.wanderly.observability.AppLogger

import android.location.Location
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.data.Mission
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import kotlinx.coroutines.launch

class MapViewModel(private val repository: WanderlyRepository) : ViewModel() {
    private val _activeMission = MutableLiveData<Mission?>()
    val activeMission: LiveData<Mission?> = _activeMission

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
                val profile = repository.getCurrentProfile() ?: return@launch

                val lastLat = profile.last_lat ?: 0.0
                val lastLng = profile.last_lng ?: 0.0
                val results = FloatArray(1)
                Location.distanceBetween(lastLat, lastLng, lat, lng, results)

                if (results[0] > 50.0 || profile.last_lat == null) {
                    repository.updateProfile(profile.copy(last_lat = lat, last_lng = lng))
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
