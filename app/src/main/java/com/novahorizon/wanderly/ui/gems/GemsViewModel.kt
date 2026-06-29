package com.novahorizon.wanderly.ui.gems

import com.novahorizon.wanderly.observability.AppLogger

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Gem
import com.novahorizon.wanderly.data.GemDiscoveryResult
import com.novahorizon.wanderly.data.HiddenGemCandidateResult
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class GemsViewModel @Inject constructor(
    private val repository: WanderlyRepository
) : ViewModel() {

    private val seenGemsHistory = mutableSetOf<String>()

    private val discoverInFlight = AtomicBoolean(false)

    private val _gemsState = MutableLiveData<GemsState>(GemsState.Idle)
    val gemsState: LiveData<GemsState> = _gemsState

    private val _message = MutableLiveData<UiText?>()
    val message: LiveData<UiText?> = _message

    private val _discoverEvent = MutableLiveData<DiscoverEvent?>()
    val discoverEvent: LiveData<DiscoverEvent?> = _discoverEvent

    private val _discoveredGems = MutableLiveData<Set<String>>(emptySet())
    val discoveredGems: LiveData<Set<String>> = _discoveredGems

    private val _currentLocation = MutableLiveData<Pair<Double, Double>?>(null)
    val currentLocation: LiveData<Pair<Double, Double>?> = _currentLocation

    private var currentLat: Double? = null
    private var currentLng: Double? = null

    sealed class GemsState {
        object Idle : GemsState()
        data class Loading(val message: UiText) : GemsState()
        data class Loaded(val gems: List<Gem>) : GemsState()
        data class Empty(val message: UiText) : GemsState()
        data class Error(val message: UiText) : GemsState()
    }

    sealed class DiscoverEvent {
        data class Discovered(val rewardHoney: Int, val firstGem: Boolean) : DiscoverEvent()
        object AlreadyDiscovered : DiscoverEvent()
        object TooFar : DiscoverEvent()
        object Failed : DiscoverEvent()
    }

    fun updateCurrentLocation(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
        _currentLocation.postValue(lat to lng)
    }

    fun discoverGem(gem: Gem) {
        val lat = currentLat
        val lng = currentLng
        if (lat == null || lng == null) {
            _discoverEvent.postValue(DiscoverEvent.TooFar)
            return
        }
        if (!discoverInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                when (val result = repository.discoverGem(gem, lat, lng)) {
                    is GemDiscoveryResult.Success -> {
                        markDiscovered(gem.name)
                        val total = repository.countGemDiscoveries()
                        // reward_honey is a delta; re-fetch authoritative balance for the UI.
                        repository.getCurrentProfile()
                        _discoverEvent.postValue(
                            DiscoverEvent.Discovered(result.rewardHoney, firstGem = total == 1)
                        )
                    }
                    GemDiscoveryResult.AlreadyDiscovered -> {
                        markDiscovered(gem.name)
                        _discoverEvent.postValue(DiscoverEvent.AlreadyDiscovered)
                    }
                    GemDiscoveryResult.TooFar -> _discoverEvent.postValue(DiscoverEvent.TooFar)
                    GemDiscoveryResult.Unauthenticated,
                    GemDiscoveryResult.Error -> _discoverEvent.postValue(DiscoverEvent.Failed)
                }
            } finally {
                discoverInFlight.set(false)
            }
        }
    }

    private fun markDiscovered(name: String) {
        _discoveredGems.postValue((_discoveredGems.value ?: emptySet()) + name)
    }

    fun clearDiscoverEvent() {
        _discoverEvent.value = null
    }

    fun loadGems(lat: Double, lng: Double, city: String) {
        updateCurrentLocation(lat, lng)
        viewModelScope.launch {
            try {
                _gemsState.postValue(
                    GemsState.Loading(
                        UiText.resource(R.string.gems_loading_city_format, city)
                    )
                )

                val candidates = when (
                    val result = repository.fetchHiddenGemCandidatesResult(lat, lng, 2500, city)
                ) {
                    is HiddenGemCandidateResult.Success -> result.candidates
                    is HiddenGemCandidateResult.Error -> {
                        handleCandidateLoadError(result)
                        return@launch
                    }
                }
                    .filterNot { seenGemsHistory.contains(it.name) }
                    .take(40)

                if (candidates.isEmpty()) {
                    _gemsState.postValue(
                        GemsState.Empty(UiText.resource(R.string.gems_empty_state))
                    )
                    _message.postValue(UiText.resource(R.string.gems_no_fresh_results))
                    return@launch
                }

                val gems = repository.curateHiddenGems(city, candidates, seenGemsHistory)
                gems.forEach { gem -> seenGemsHistory.add(gem.name) }
                trimSeenGemsHistory()

                if (gems.isEmpty()) {
                    _gemsState.postValue(
                        GemsState.Empty(UiText.resource(R.string.gems_empty_state))
                    )
                    _message.postValue(UiText.resource(R.string.gems_no_fresh_results))
                } else {
                    _gemsState.postValue(GemsState.Loaded(gems))
                }
            } catch (e: Exception) {
                CrashReporter.recordNonFatal(
                    CrashEvent.GEMS_LOAD_FAILED,
                    e,
                    CrashKey.COMPONENT to "gems",
                    CrashKey.OPERATION to "load"
                )
                if (BuildConfig.DEBUG) {
                    AppLogger.e("GemsViewModel", "Error loading gems", e)
                }
                val message = UiText.resource(R.string.gems_loading_failed)
                _gemsState.postValue(GemsState.Error(message))
                _message.postValue(message)
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun handleCandidateLoadError(error: HiddenGemCandidateResult.Error) {
        val exception = IllegalStateException(
            "Hidden gem candidate load failed: ${error.reason}, status=${error.statusCode}"
        )
        CrashReporter.recordNonFatal(
            CrashEvent.GEMS_LOAD_FAILED,
            exception,
            CrashKey.COMPONENT to "gems",
            CrashKey.OPERATION to "load_candidates"
        )
        if (BuildConfig.DEBUG) {
            AppLogger.e("GemsViewModel", "Hidden gem candidate load failed [${error.reason}, status=${error.statusCode}]")
        }
        val message = UiText.resource(R.string.gems_loading_failed)
        _gemsState.postValue(GemsState.Error(message))
        _message.postValue(message)
    }

    private fun trimSeenGemsHistory() {
        while (seenGemsHistory.size > MAX_SEEN_GEMS) {
            val eldest = seenGemsHistory.iterator().next()
            seenGemsHistory.remove(eldest)
        }
    }

    private companion object {
        // Cap the in-memory seen-gems set so it can't grow unbounded across many cities/sessions.
        private const val MAX_SEEN_GEMS = 500
    }
}
