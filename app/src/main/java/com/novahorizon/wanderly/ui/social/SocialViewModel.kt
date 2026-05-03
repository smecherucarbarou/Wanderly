package com.novahorizon.wanderly.ui.social

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.AddFriendResult
import com.novahorizon.wanderly.data.FriendRequestActionResult
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SocialViewModel @Inject constructor(
    private val repository: WanderlyRepository
) : ViewModel() {

    private val _leaderboard = MutableLiveData<List<Profile>>()
    val leaderboard: LiveData<List<Profile>> = _leaderboard

    private val _friends = MutableLiveData<List<Profile>>()
    val friends: LiveData<List<Profile>> = _friends

    private val _incomingFriendRequests = MutableLiveData<List<Profile>>()
    val incomingFriendRequests: LiveData<List<Profile>> = _incomingFriendRequests

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _addFriendResult = MutableLiveData<SocialMessage?>()
    val addFriendResult: LiveData<SocialMessage?> = _addFriendResult

    private val _state = MutableStateFlow<SocialUiState>(SocialUiState.Loading)
    val state: StateFlow<SocialUiState> = _state

    sealed class SocialUiState {
        object Loading : SocialUiState()
        data class Loaded(
            val friends: List<Profile>,
            val leaderboard: List<Profile>,
            val incomingRequests: List<Profile> = emptyList()
        ) : SocialUiState()
        object Empty : SocialUiState()
        data class Error(val message: UiText) : SocialUiState()
    }

    data class SocialMessage(
        val message: UiText,
        val isError: Boolean
    )

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
                _state.value = SocialUiState.Error(UiText.resource(R.string.error_network))
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
                refreshFriendsState()
            } catch (_: Exception) {
                _state.value = SocialUiState.Error(UiText.resource(R.string.error_network))
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
                val result = repository.addFriendByCodeResult(friendCode)
                val feedback = addFriendDisplayMessage(result)
                _addFriendResult.postValue(feedback)
                when (result) {
                    AddFriendResult.FriendRequestSent,
                    AddFriendResult.AlreadyRequestedOrFriends -> {
                        restoreSocialState()
                        _isLoading.postValue(false)
                    }
                    else -> {
                        _state.value = SocialUiState.Error(feedback.message)
                        _isLoading.postValue(false)
                    }
                }
            } catch (_: Exception) {
                _addFriendResult.postValue(
                    SocialMessage(
                        message = UiText.resource(R.string.social_add_friend_failed),
                        isError = true
                    )
                )
                _state.value = SocialUiState.Error(UiText.resource(R.string.error_network))
                _isLoading.postValue(false)
            }
        }
    }

    fun acceptFriendRequest(requesterId: String) {
        updateFriendRequest(
            requesterId = requesterId,
            action = repository::acceptFriendRequest
        )
    }

    fun rejectFriendRequest(requesterId: String) {
        updateFriendRequest(
            requesterId = requesterId,
            action = repository::rejectFriendRequest
        )
    }

    fun removeFriend(friendId: String) {
        _isLoading.value = true
        _state.value = SocialUiState.Loading
        viewModelScope.launch {
            try {
                val success = repository.removeFriend(friendId)
                if (success) {
                    _addFriendResult.postValue(
                        SocialMessage(
                            message = UiText.DynamicString("Friend removed successfully"),
                            isError = false
                        )
                    )
                    loadFriends()
                } else {
                    _addFriendResult.postValue(
                        SocialMessage(
                            message = UiText.DynamicString("Failed to remove friend"),
                            isError = true
                        )
                    )
                    _state.value = SocialUiState.Error(UiText.resource(R.string.error_network))
                    _isLoading.postValue(false)
                }
            } catch (_: Exception) {
                _addFriendResult.postValue(
                    SocialMessage(
                        message = UiText.DynamicString("Failed to remove friend"),
                        isError = true
                    )
                )
                _state.value = SocialUiState.Error(UiText.resource(R.string.error_network))
                _isLoading.postValue(false)
            }
        }
    }
    
    fun clearAddFriendResult() {
        _addFriendResult.value = null
    }

    private fun addFriendDisplayMessage(result: AddFriendResult): SocialMessage {
        return when (result) {
            AddFriendResult.FriendRequestSent -> SocialMessage(
                message = UiText.resource(R.string.social_friend_request_sent),
                isError = false
            )
            AddFriendResult.AlreadyRequestedOrFriends -> SocialMessage(
                message = UiText.resource(R.string.social_friend_request_pending),
                isError = false
            )
            AddFriendResult.NotAuthenticated -> SocialMessage(
                message = UiText.resource(R.string.social_friend_auth_required),
                isError = true
            )
            AddFriendResult.InvalidFriendCode -> SocialMessage(
                message = UiText.resource(R.string.social_friend_code_invalid),
                isError = true
            )
            AddFriendResult.FriendCodeNotFound -> SocialMessage(
                message = UiText.resource(R.string.social_friend_code_not_found),
                isError = true
            )
            AddFriendResult.SelfFriend -> SocialMessage(
                message = UiText.resource(R.string.social_friend_self),
                isError = true
            )
            AddFriendResult.Failure -> SocialMessage(
                message = UiText.resource(R.string.social_add_friend_failed),
                isError = true
            )
        }
    }

    private fun updateFriendRequest(
        requesterId: String,
        action: suspend (String) -> FriendRequestActionResult
    ) {
        if (requesterId.isBlank()) return
        _isLoading.value = true
        _state.value = SocialUiState.Loading
        viewModelScope.launch {
            try {
                val result = action(requesterId)
                val feedback = friendRequestActionDisplayMessage(result)
                _addFriendResult.postValue(feedback)
                when (result) {
                    FriendRequestActionResult.Accepted,
                    FriendRequestActionResult.Rejected -> refreshFriendsState()
                    else -> _state.value = SocialUiState.Error(feedback.message)
                }
            } catch (_: Exception) {
                val feedback = SocialMessage(
                    message = UiText.resource(R.string.social_friend_request_action_failed),
                    isError = true
                )
                _addFriendResult.postValue(feedback)
                _state.value = SocialUiState.Error(feedback.message)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private suspend fun refreshFriendsState() {
        val acceptedFriends = repository.getFriends()
        val incomingRequests = repository.getIncomingFriendRequests()
        _friends.postValue(acceptedFriends)
        _incomingFriendRequests.postValue(incomingRequests)
        _state.value = if (acceptedFriends.isEmpty() && incomingRequests.isEmpty()) {
            SocialUiState.Empty
        } else {
            SocialUiState.Loaded(
                friends = acceptedFriends,
                leaderboard = _leaderboard.value.orEmpty(),
                incomingRequests = incomingRequests
            )
        }
    }

    private fun friendRequestActionDisplayMessage(result: FriendRequestActionResult): SocialMessage {
        return when (result) {
            FriendRequestActionResult.Accepted -> SocialMessage(
                message = UiText.resource(R.string.social_friend_request_accepted),
                isError = false
            )
            FriendRequestActionResult.Rejected -> SocialMessage(
                message = UiText.resource(R.string.social_friend_request_rejected),
                isError = false
            )
            FriendRequestActionResult.NotAuthenticated -> SocialMessage(
                message = UiText.resource(R.string.social_friend_auth_required),
                isError = true
            )
            FriendRequestActionResult.NotPendingRequest,
            FriendRequestActionResult.Failure -> SocialMessage(
                message = UiText.resource(R.string.social_friend_request_action_failed),
                isError = true
            )
        }
    }

    private fun restoreSocialState() {
        val currentFriends = _friends.value.orEmpty()
        val currentLeaderboard = _leaderboard.value.orEmpty()
        val incomingRequests = _incomingFriendRequests.value.orEmpty()
        _state.value = if (currentFriends.isEmpty() && currentLeaderboard.isEmpty() && incomingRequests.isEmpty()) {
            SocialUiState.Empty
        } else {
            SocialUiState.Loaded(
                friends = currentFriends,
                leaderboard = currentLeaderboard,
                incomingRequests = incomingRequests
            )
        }
    }
}
