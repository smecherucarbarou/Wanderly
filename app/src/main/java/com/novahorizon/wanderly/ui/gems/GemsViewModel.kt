package com.novahorizon.wanderly.ui.gems

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Gem
import com.novahorizon.wanderly.data.WanderlyRepository
import kotlinx.coroutines.launch

class GemsViewModel(private val repository: WanderlyRepository) : ViewModel() {

    private val seenGemsHistory = mutableSetOf<String>()

    private val _gemsState = MutableLiveData<GemsState>(GemsState.Idle)
    val gemsState: LiveData<GemsState> = _gemsState

    private val _message = MutableLiveData<String?>()
    val message: LiveData<String?> = _message

    sealed class GemsState {
        object Idle : GemsState()
        data class Loading(val message: String) : GemsState()
        data class Loaded(val gems: List<Gem>) : GemsState()
        data class Empty(val message: String) : GemsState()
        data class Error(val message: String) : GemsState()
    }

    fun loadGems(lat: Double, lng: Double, city: String) {
        viewModelScope.launch {
            try {
                _gemsState.postValue(
                    GemsState.Loading(
                        repository.context.getString(R.string.gems_loading_city_format, city)
                    )
                )

                val candidates = repository.fetchHiddenGemCandidates(lat, lng, 2500, city)
                    .filterNot { seenGemsHistory.contains(it.name) }
                    .take(40)

                if (candidates.isEmpty()) {
                    _gemsState.postValue(
                        GemsState.Empty(repository.context.getString(R.string.gems_empty_state))
                    )
                    _message.postValue(repository.context.getString(R.string.gems_no_fresh_results))
                    return@launch
                }

                val gems = repository.curateHiddenGems(city, candidates, seenGemsHistory)
                gems.forEach { gem -> seenGemsHistory.add(gem.name) }

                if (gems.isEmpty()) {
                    _gemsState.postValue(
                        GemsState.Empty(repository.context.getString(R.string.gems_empty_state))
                    )
                    _message.postValue(repository.context.getString(R.string.gems_no_fresh_results))
                } else {
                    _gemsState.postValue(GemsState.Loaded(gems))
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e("GemsViewModel", "Error loading gems", e)
                }
                val message = repository.context.getString(R.string.gems_loading_failed)
                _gemsState.postValue(GemsState.Error(message))
                _message.postValue(message)
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
