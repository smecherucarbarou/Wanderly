package com.novahorizon.wanderly.ui.profile

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.data.AuthRepository
import com.novahorizon.wanderly.data.AvatarUploadResult
import com.novahorizon.wanderly.data.LogoutCoordinator
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileError
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.ProfileUpdateResult
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: WanderlyRepository,
    private val profileStateProvider: ProfileStateProvider,
    private val authRepository: AuthRepository = WanderlyGraph.authRepository(),
    private val logoutCoordinator: LogoutCoordinator? = null
) : ViewModel() {
    private var hasCheckedBadgesThisSession = false
    private var profileCollectorJob: Job? = null
    private val defaultLogoutCoordinator by lazy {
        LogoutCoordinator.create(
            repository.context,
            authRepository,
            repository
        )
    }

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
        data class ShowMessage(val message: UiText, val isError: Boolean) : ProfileEvent()
        data class AvatarUpdated(val remotePath: String) : ProfileEvent()
        data class ClassLocked(val className: String) : ProfileEvent()
        object LoggedOut : ProfileEvent()
    }

    sealed class ProfileUiState {
        object Loading : ProfileUiState()
        data class Loaded(val profile: Profile) : ProfileUiState()
        data class Error(val message: UiText) : ProfileUiState()
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
                    _profileState.postValue(ProfileUiState.Error(UiText.resource(R.string.profile_load_failed)))
                    _profileEvent.postValue(ProfileEvent.LoggedOut)
                    return@launch
                }
                _profile.postValue(profile)
                _profileState.postValue(ProfileUiState.Loaded(profile))
                if (!hasCheckedBadgesThisSession) {
                    hasCheckedBadgesThisSession = true
                    checkAndUnlockBadges(profile)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _profileState.postValue(ProfileUiState.Error(UiText.resource(R.string.profile_load_failed)))
            }
        }
    }

    fun uploadAvatar(profile: Profile, uri: Uri) {
        viewModelScope.launch {
            try {
                val avatarUrl = when (val uploadResult = repository.uploadAvatar(uri, profile.id)) {
                    is AvatarUploadResult.Success -> uploadResult.path
                    is AvatarUploadResult.Error -> {
                        _profileEvent.postValue(
                            ProfileEvent.ShowMessage(
                                UiText.DynamicString(uploadResult.message),
                                isError = true
                            )
                        )
                        return@launch
                    }
                    AvatarUploadResult.FileTooLarge -> {
                        _profileEvent.postValue(
                            ProfileEvent.ShowMessage(
                                UiText.resource(R.string.profile_avatar_upload_failed),
                                isError = true
                            )
                        )
                        return@launch
                    }
                    AvatarUploadResult.UnsupportedFormat -> {
                        _profileEvent.postValue(
                            ProfileEvent.ShowMessage(
                                UiText.resource(R.string.profile_avatar_upload_failed),
                                isError = true
                            )
                        )
                        return@launch
                    }
                }
                val updatedProfile = profile.copy(avatar_url = avatarUrl)
                when (repository.updateProfileDetailed(updatedProfile)) {
                    is ProfileUpdateResult.Success -> Unit
                    is ProfileUpdateResult.Error -> {
                        _profileEvent.postValue(
                            ProfileEvent.ShowMessage(
                                UiText.resource(R.string.profile_avatar_upload_failed),
                                isError = true
                            )
                        )
                        return@launch
                    }
                }
                _profileEvent.postValue(ProfileEvent.AvatarUpdated(avatarUrl))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _profileEvent.postValue(
                    ProfileEvent.ShowMessage(
                        UiText.resource(R.string.profile_avatar_upload_failed),
                        isError = true
                    )
                )
            }
        }
    }

    fun updateUsername(profile: Profile, newUsername: String) {
        viewModelScope.launch {
            try {
                when (val result = repository.updateUsername(newUsername)) {
                    is ProfileUpdateResult.Success -> {
                        _profile.postValue(result.profile)
                        _profileState.postValue(ProfileUiState.Loaded(result.profile))
                    }
                    is ProfileUpdateResult.Error -> {
                        when (result.error) {
                            ProfileError.UsernameTaken -> {
                                _profileEvent.postValue(
                                    ProfileEvent.ShowMessage(
                                        UiText.resource(R.string.profile_username_taken),
                                        isError = true
                                    )
                                )
                            }
                            ProfileError.InvalidUsername -> {
                                _profileEvent.postValue(
                                    ProfileEvent.ShowMessage(
                                        UiText.resource(R.string.profile_username_invalid),
                                        isError = true
                                    )
                                )
                            }
                            ProfileError.Unauthenticated -> {
                                _profileEvent.postValue(ProfileEvent.LoggedOut)
                            }
                            else -> {
                                _profileEvent.postValue(
                                    ProfileEvent.ShowMessage(
                                        UiText.resource(R.string.profile_username_update_failed),
                                        isError = true
                                    )
                                )
                            }
                        }
                        return@launch
                    }
                }
                _profileEvent.postValue(
                    ProfileEvent.ShowMessage(
                        UiText.resource(R.string.profile_username_updated),
                        isError = false
                    )
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _profileEvent.postValue(
                    ProfileEvent.ShowMessage(
                        UiText.resource(R.string.profile_username_update_failed),
                        isError = true
                    )
                )
            }
        }
    }

    fun confirmClassSelection(profile: Profile, className: String) {
        viewModelScope.launch {
            try {
                val updated = profile.copy(explorer_class = className)
                val success = repository.updateProfile(updated)
                if (success) {
                    _profileEvent.postValue(ProfileEvent.ClassLocked(className))
                } else {
                    _profileEvent.postValue(
                        ProfileEvent.ShowMessage(
                            UiText.resource(R.string.profile_class_lock_failed),
                            isError = true
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _profileEvent.postValue(
                    ProfileEvent.ShowMessage(
                        UiText.resource(R.string.profile_class_lock_failed),
                        isError = true
                    )
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                val logoutCoordinator = logoutCoordinator ?: defaultLogoutCoordinator
                val result = logoutCoordinator.logoutCompletely()
                if (result.signedOut) {
                    _profileEvent.postValue(ProfileEvent.LoggedOut)
                } else {
                    _profileEvent.postValue(
                        ProfileEvent.ShowMessage(
                            UiText.resource(R.string.profile_logout_failed),
                            isError = true
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _profileEvent.postValue(
                    ProfileEvent.ShowMessage(
                        UiText.resource(R.string.profile_logout_failed),
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
