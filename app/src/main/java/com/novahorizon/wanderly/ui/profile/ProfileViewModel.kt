package com.novahorizon.wanderly.ui.profile

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.WanderlyRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val repository: WanderlyRepository,
    private val profileStateProvider: ProfileStateProvider
) : ViewModel() {
    private var hasCheckedBadgesThisSession = false
    private var profileCollectorJob: Job? = null

    private val _profile = MutableLiveData<Profile?>()
    val profile: LiveData<Profile?> = _profile

    private val _profileState = MutableLiveData<ProfileUiState>(ProfileUiState.Loading)
    val profileState: LiveData<ProfileUiState> = _profileState

    private val _profileEvent = MutableLiveData<ProfileEvent?>()
    val profileEvent: LiveData<ProfileEvent?> = _profileEvent

    init {
        startProfileCollector()
    }

    sealed class ProfileEvent {
        data class ShowMessage(@param:StringRes val messageRes: Int, val isError: Boolean) : ProfileEvent()
        data class AvatarUpdated(val remotePath: String) : ProfileEvent()
        data class ClassLocked(val className: String) : ProfileEvent()
        object LoggedOut : ProfileEvent()
    }

    sealed class ProfileUiState {
        object Loading : ProfileUiState()
        data class Loaded(val profile: Profile) : ProfileUiState()
        data class Error(@param:StringRes val messageRes: Int) : ProfileUiState()
    }

    fun loadProfile() {
        if (profileCollectorJob?.isActive != true) {
            startProfileCollector()
        }
        _profileState.value = ProfileUiState.Loading
        viewModelScope.launch {
            try {
                val profile = profileStateProvider.refreshProfile()
                if (profile == null) {
                    _profileState.postValue(ProfileUiState.Error(R.string.profile_load_failed))
                    return@launch
                }
                _profile.postValue(profile)
                _profileState.postValue(ProfileUiState.Loaded(profile))
                if (!hasCheckedBadgesThisSession) {
                    hasCheckedBadgesThisSession = true
                    checkAndUnlockBadges(profile)
                }
            } catch (_: Exception) {
                _profileState.postValue(ProfileUiState.Error(R.string.profile_load_failed))
            }
        }
    }

    fun uploadAvatar(profile: Profile, uri: Uri) {
        viewModelScope.launch {
            try {
                val avatarUrl = repository.uploadAvatar(uri, profile.id)
                val updatedProfile = profile.copy(avatar_url = avatarUrl)
                if (!repository.updateProfile(updatedProfile)) {
                    throw IllegalStateException("Avatar uploaded but profile update failed")
                }
                _profileEvent.postValue(ProfileEvent.AvatarUpdated(avatarUrl))
            } catch (_: Exception) {
                _profileEvent.postValue(
                    ProfileEvent.ShowMessage(
                        R.string.profile_avatar_upload_failed,
                        isError = true
                    )
                )
            }
        }
    }

    fun updateUsername(profile: Profile, newUsername: String) {
        viewModelScope.launch {
            try {
                val updatedProfile = profile.copy(username = newUsername)
                if (!repository.updateProfile(updatedProfile)) {
                    throw IllegalStateException("Profile update failed")
                }
                _profileEvent.postValue(
                    ProfileEvent.ShowMessage(
                        R.string.profile_username_updated,
                        isError = false
                    )
                )
            } catch (_: Exception) {
                _profileEvent.postValue(
                    ProfileEvent.ShowMessage(
                        R.string.profile_username_update_failed,
                        isError = true
                    )
                )
            }
        }
    }

    fun confirmClassSelection(profile: Profile, className: String) {
        viewModelScope.launch {
            val updated = profile.copy(explorer_class = className)
            val success = repository.updateProfile(updated)
            if (success) {
                _profileEvent.postValue(ProfileEvent.ClassLocked(className))
            } else {
                _profileEvent.postValue(
                    ProfileEvent.ShowMessage(
                        R.string.profile_class_lock_failed,
                        isError = true
                    )
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                SupabaseClient.client.auth.signOut()
                repository.clearRememberMe()
                repository.clearLocalState()
                _profileEvent.postValue(ProfileEvent.LoggedOut)
            } catch (_: Exception) {
                _profileEvent.postValue(
                    ProfileEvent.ShowMessage(
                        R.string.profile_logout_failed,
                        isError = true
                    )
                )
            }
        }
    }

    fun clearProfileEvent() {
        _profileEvent.value = null
    }

    suspend fun checkAndUnlockBadges(profile: Profile) {
        val updatedProfile = ProfileBadgeEvaluator.updatedProfileWithUnlockedBadges(profile)
        val currentBadges = profile.badges?.toSet().orEmpty()
        val newBadges = updatedProfile.badges?.toSet().orEmpty()

        if (newBadges.size > currentBadges.size) {
            repository.updateProfile(updatedProfile)
        }
    }

    private fun startProfileCollector() {
        profileCollectorJob?.cancel()
        profileCollectorJob = profileStateProvider.collectProfile(viewModelScope) { profile ->
            _profile.postValue(profile)
            if (profile != null) {
                _profileState.postValue(ProfileUiState.Loaded(profile))
            }
        }
    }
}
