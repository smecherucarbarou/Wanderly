package com.novahorizon.wanderly.ui.social

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
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

    fun loadLeaderboard() {
        _isLoading.value = true
        viewModelScope.launch {
            val list = repository.getLeaderboard()
            _leaderboard.postValue(list)
            _isLoading.postValue(false)
        }
    }

    fun loadFriends() {
        _isLoading.value = true
        viewModelScope.launch {
            val list = repository.getFriends()
            _friends.postValue(list)
            _isLoading.postValue(false)
        }
    }

    fun addFriend(friendCode: String) {
        if (friendCode.isBlank()) return
        _isLoading.value = true
        viewModelScope.launch {
            val resultMessage = repository.addFriendByCode(friendCode)
            _addFriendResult.postValue(resultMessage)
            if (resultMessage == "Friend added successfully!") {
                loadFriends() // refresh list
            } else {
                _isLoading.postValue(false)
            }
        }
    }

    fun removeFriend(friendId: String) {
        _isLoading.value = true
        viewModelScope.launch {
            val success = repository.removeFriend(friendId)
            if (success) {
                _addFriendResult.postValue("Friend removed successfully")
                loadFriends()
            } else {
                _addFriendResult.postValue("Failed to remove friend")
                _isLoading.postValue(false)
            }
        }
    }
    
    fun clearAddFriendResult() {
        _addFriendResult.value = null
    }
}
