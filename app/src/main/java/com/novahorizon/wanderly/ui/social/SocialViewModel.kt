package com.novahorizon.wanderly.ui.social

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SocialViewModel(private val repository: WanderlyRepository) : ViewModel() {

    private val _leaderboard = MutableLiveData<List<Profile>>()
    val leaderboard: LiveData<List<Profile>> = _leaderboard

    private val _friends = MutableLiveData<List<Profile>>()
    val friends: LiveData<List<Profile>> = _friends

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _addFriendResult = MutableLiveData<String?>()
    val addFriendResult: LiveData<String?> = _addFriendResult

    private val _state = MutableStateFlow<SocialUiState>(SocialUiState.Loading)
    val state: StateFlow<SocialUiState> = _state

    sealed class SocialUiState {
        object Loading : SocialUiState()
        data class Loaded(
            val friends: List<Profile>,
            val leaderboard: List<Profile>
        ) : SocialUiState()
        object Empty : SocialUiState()
        data class Error(val messageRes: Int) : SocialUiState()
    }

    fun loadLeaderboard() {
        _isLoading.value = true
        _state.value = SocialUiState.Loading
        viewModelScope.launch {
            try {
                val list = repository.getLeaderboard()
                _leaderboard.postValue(list)
                _state.value = if (list.isEmpty()) {
                    SocialUiState.Empty
                } else {
                    SocialUiState.Loaded(
                        friends = _friends.value.orEmpty(),
                        leaderboard = list
                    )
                }
            } catch (_: Exception) {
                _state.value = SocialUiState.Error(R.string.error_network)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun loadFriends() {
        _isLoading.value = true
        _state.value = SocialUiState.Loading
        viewModelScope.launch {
            try {
                val list = repository.getFriends()
                _friends.postValue(list)
                _state.value = if (list.isEmpty()) {
                    SocialUiState.Empty
                } else {
                    SocialUiState.Loaded(
                        friends = list,
                        leaderboard = _leaderboard.value.orEmpty()
                    )
                }
            } catch (_: Exception) {
                _state.value = SocialUiState.Error(R.string.error_network)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun addFriend(friendCode: String) {
        if (friendCode.isBlank()) return
        _isLoading.value = true
        _state.value = SocialUiState.Loading
        viewModelScope.launch {
            try {
                val resultMessage = repository.addFriendByCode(friendCode)
                _addFriendResult.postValue(addFriendDisplayMessage(resultMessage))
                if (resultMessage == "Friend added successfully!") {
                    loadFriends() // refresh list
                } else {
                    _state.value = SocialUiState.Error(addFriendErrorMessage(resultMessage))
                    _isLoading.postValue(false)
                }
            } catch (_: Exception) {
                _addFriendResult.postValue(
                    repository.context.getString(R.string.social_add_friend_failed)
                )
                _state.value = SocialUiState.Error(R.string.error_network)
                _isLoading.postValue(false)
            }
        }
    }

    fun removeFriend(friendId: String) {
        _isLoading.value = true
        _state.value = SocialUiState.Loading
        viewModelScope.launch {
            try {
                val success = repository.removeFriend(friendId)
                if (success) {
                    _addFriendResult.postValue("Friend removed successfully")
                    loadFriends()
                } else {
                    _addFriendResult.postValue("Failed to remove friend")
                    _state.value = SocialUiState.Error(R.string.error_network)
                    _isLoading.postValue(false)
                }
            } catch (_: Exception) {
                _addFriendResult.postValue("Failed to remove friend")
                _state.value = SocialUiState.Error(R.string.error_network)
                _isLoading.postValue(false)
            }
        }
    }
    
    fun clearAddFriendResult() {
        _addFriendResult.value = null
    }

    private fun addFriendDisplayMessage(resultMessage: String): String {
        return when {
            resultMessage == "Already friends with this user" ->
                repository.context.getString(R.string.social_friend_already_added)
            resultMessage.startsWith("Failed to add friend") ->
                repository.context.getString(R.string.social_add_friend_failed)
            else -> resultMessage
        }
    }

    private fun addFriendErrorMessage(resultMessage: String): Int {
        return when {
            resultMessage == "Already friends with this user" -> R.string.social_friend_already_added
            resultMessage.startsWith("Failed to add friend") -> R.string.social_add_friend_failed
            else -> R.string.error_network
        }
    }
}
