package com.novahorizon.wanderly.ui.profile

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.AuthRepository
import com.novahorizon.wanderly.data.AvatarUploadResult
import com.novahorizon.wanderly.data.LogoutCoordinator
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileError
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.ProfileUpdateResult
import com.novahorizon.wanderly.data.SensitiveProfileMutationResult
import com.novahorizon.wanderly.data.ShopEquipResult
import com.novahorizon.wanderly.data.ShopItemStatus
import com.novahorizon.wanderly.data.ShopPurchaseResult
import com.novahorizon.wanderly.data.StreakMilestoneStatus
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: WanderlyRepository,
    private val profileStateProvider: ProfileStateProvider,
    private val authRepository: AuthRepository,
    private val logoutCoordinator: LogoutCoordinator
) : ViewModel() {
    private var profileCollectorJob: Job? = null

    private val _profile = MutableLiveData<Profile?>()
    val profile: LiveData<Profile?> = _profile

    private val _profileState = MutableLiveData<ProfileUiState>(ProfileUiState.Loading)
    val profileState: LiveData<ProfileUiState> = _profileState

    private val _profileEvent = MutableLiveData<ProfileEvent?>()
    val profileEvent: LiveData<ProfileEvent?> = _profileEvent

    private val _avatarUploadState = MutableLiveData<AvatarUploadState>(AvatarUploadState.Idle)
    val avatarUploadState: LiveData<AvatarUploadState> = _avatarUploadState

    private val _streakMilestones = MutableLiveData<List<StreakMilestoneStatus>>(emptyList())
    val streakMilestones: LiveData<List<StreakMilestoneStatus>> = _streakMilestones

    private val _referralAvailable = MutableLiveData(false)
    val referralAvailable: LiveData<Boolean> = _referralAvailable

    private val _gemsFound = MutableLiveData(0)
    val gemsFound: LiveData<Int> = _gemsFound

    private val _shopItems = MutableLiveData<List<ShopItemStatus>>(emptyList())
    val shopItems: LiveData<List<ShopItemStatus>> = _shopItems

    private val _purchaseInFlight = MutableStateFlow(false)
    val purchaseInFlight: StateFlow<Boolean> = _purchaseInFlight.asStateFlow()

    private val _claimReferralInFlight = MutableStateFlow(false)
    val claimReferralInFlight: StateFlow<Boolean> = _claimReferralInFlight.asStateFlow()

    private val _claimMilestoneInFlight = MutableStateFlow(false)
    val claimMilestoneInFlight: StateFlow<Boolean> = _claimMilestoneInFlight.asStateFlow()

    private val _equipInFlight = MutableStateFlow(false)
    val equipInFlight: StateFlow<Boolean> = _equipInFlight.asStateFlow()

    init {
        startProfileCollector()
    }

    sealed class ProfileEvent {
        data class ShowMessage(val message: UiText, val isError: Boolean) : ProfileEvent()
        data class AvatarUpdated(val avatarUrl: String) : ProfileEvent()
        data class ClassLocked(val className: String) : ProfileEvent()
        object LoggedOut : ProfileEvent()
    }

    sealed class ProfileUiState {
        object Loading : ProfileUiState()
        data class Loaded(val profile: Profile) : ProfileUiState()
        object Empty : ProfileUiState()
        data class Error(val message: UiText) : ProfileUiState()
    }

    sealed class AvatarUploadState {
        object Idle : AvatarUploadState()
        object Uploading : AvatarUploadState()
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
                    _profile.value = null
                    _profileState.value = ProfileUiState.Empty
                    return@launch
                }
                val displayProfile = mergeWithOptimisticProfile(profile)
                _profile.value = displayProfile
                _profileState.value = ProfileUiState.Loaded(displayProfile)
                loadStreakMilestones()
                loadReferralState()
                loadGemsFound()
                loadShopItems()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _profileState.value = ProfileUiState.Error(UiText.resource(R.string.profile_load_failed))
            }
        }
    }

    fun uploadAvatar(profile: Profile, uri: Uri) {
        viewModelScope.launch {
            _avatarUploadState.value = AvatarUploadState.Uploading
            try {
                val avatarUrl = when (val uploadResult = repository.uploadAvatar(uri, profile.id)) {
                    is AvatarUploadResult.Success -> uploadResult.avatarUrl

                    is AvatarUploadResult.Error -> {
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.DynamicString(uploadResult.message),
                            isError = true
                        )
                        return@launch
                    }

                    AvatarUploadResult.FileTooLarge -> {
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.resource(R.string.profile_avatar_upload_failed),
                            isError = true
                        )
                        return@launch
                    }

                    AvatarUploadResult.UnsupportedFormat -> {
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.resource(R.string.profile_avatar_upload_failed),
                            isError = true
                        )
                        return@launch
                    }
                }

                val updatedProfile = profile.copy(avatar_url = avatarUrl)
                _profile.value = updatedProfile
                _profileState.value = ProfileUiState.Loaded(updatedProfile)
                _profileEvent.value = ProfileEvent.AvatarUpdated(avatarUrl)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _profileEvent.value = ProfileEvent.ShowMessage(
                    UiText.resource(R.string.profile_avatar_upload_failed),
                    isError = true
                )
            } finally {
                _avatarUploadState.value = AvatarUploadState.Idle
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

    fun confirmClassSelection(
        @Suppress("UNUSED_PARAMETER") profile: Profile,
        @Suppress("UNUSED_PARAMETER") className: String
    ) {
        _profileEvent.value = ProfileEvent.ShowMessage(
            UiText.resource(R.string.profile_class_lock_failed),
            isError = true
        )
    }

    fun logout() {
        viewModelScope.launch {
            try {
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

    fun useStreakFreeze() {
        viewModelScope.launch {
            when (val result = repository.useStreakFreeze()) {
                is SensitiveProfileMutationResult.Success -> {
                    result.profile?.let { updated ->
                        _profile.value = updated
                        _profileState.value = ProfileUiState.Loaded(updated)
                    }
                    _profileEvent.value = ProfileEvent.ShowMessage(
                        UiText.resource(R.string.profile_streak_freeze_used),
                        isError = false
                    )
                }
                is SensitiveProfileMutationResult.Rejected -> {
                    val message = if (result.reason == "no_freezes") {
                        R.string.profile_streak_freeze_none
                    } else {
                        R.string.profile_streak_freeze_failed
                    }
                    _profileEvent.value = ProfileEvent.ShowMessage(
                        UiText.resource(message),
                        isError = true
                    )
                }
                SensitiveProfileMutationResult.Unauthenticated -> {
                    _profileEvent.value = ProfileEvent.LoggedOut
                }
                else -> {
                    _profileEvent.value = ProfileEvent.ShowMessage(
                        UiText.resource(R.string.profile_streak_freeze_failed),
                        isError = true
                    )
                }
            }
        }
    }

    fun claimStreakMilestone(threshold: Int) {
        if (!_claimMilestoneInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                when (val result = repository.claimStreakMilestone(threshold)) {
                    is SensitiveProfileMutationResult.Success -> {
                        result.profile?.let { updated ->
                            _profile.value = updated
                            _profileState.value = ProfileUiState.Loaded(updated)
                        }
                        loadStreakMilestones()
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.resource(R.string.profile_streak_milestone_claimed),
                            isError = false
                        )
                    }
                    is SensitiveProfileMutationResult.Rejected -> {
                        if (result.reason == "not_authenticated") {
                            _profileEvent.value = ProfileEvent.LoggedOut
                            return@launch
                        }
                        val message = when (result.reason) {
                            "already_claimed" -> R.string.profile_streak_milestone_already_claimed
                            "not_reached" -> R.string.profile_streak_milestone_locked
                            else -> R.string.profile_streak_milestone_failed
                        }
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.resource(message),
                            isError = true
                        )
                    }
                    SensitiveProfileMutationResult.Unauthenticated -> {
                        _profileEvent.value = ProfileEvent.LoggedOut
                    }
                    else -> {
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.resource(R.string.profile_streak_milestone_failed),
                            isError = true
                        )
                    }
                }
            } finally {
                _claimMilestoneInFlight.value = false
            }
        }
    }

    private suspend fun loadStreakMilestones() {
        _streakMilestones.value = repository.getStreakMilestones()
    }

    fun claimReferral(rawCode: String) {
        val code = rawCode.trim()
        if (code.isEmpty()) {
            _profileEvent.value = ProfileEvent.ShowMessage(
                UiText.resource(R.string.profile_referral_empty),
                isError = true
            )
            return
        }
        if (!_claimReferralInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                when (val result = repository.claimReferral(code)) {
                    is SensitiveProfileMutationResult.Success -> {
                        result.profile?.let { updated ->
                            _profile.value = updated
                            _profileState.value = ProfileUiState.Loaded(updated)
                        }
                        _referralAvailable.value = false
                        val reward = result.reason?.toIntOrNull() ?: 0
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.resource(R.string.profile_referral_claimed, reward),
                            isError = false
                        )
                    }
                    is SensitiveProfileMutationResult.Rejected -> {
                        if (result.reason == "not_authenticated") {
                            _profileEvent.value = ProfileEvent.LoggedOut
                            return@launch
                        }
                        if (result.reason == "already_referred") {
                            _referralAvailable.value = false
                        }
                        val message = when (result.reason) {
                            "code_not_found" -> R.string.profile_referral_not_found
                            "self_referral" -> R.string.profile_referral_self
                            "already_referred" -> R.string.profile_referral_already
                            else -> R.string.profile_referral_failed
                        }
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.resource(message),
                            isError = true
                        )
                    }
                    SensitiveProfileMutationResult.Unauthenticated -> {
                        _profileEvent.value = ProfileEvent.LoggedOut
                    }
                    else -> {
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.resource(R.string.profile_referral_failed),
                            isError = true
                        )
                    }
                }
            } finally {
                _claimReferralInFlight.value = false
            }
        }
    }

    private suspend fun loadReferralState() {
        _referralAvailable.value = !repository.hasClaimedReferral()
    }

    private suspend fun loadGemsFound() {
        _gemsFound.value = repository.countGemDiscoveries()
    }

    private suspend fun loadShopItems() {
        _shopItems.value = repository.getShopItems()
    }

    fun purchaseShopItem(itemId: String) {
        if (!_purchaseInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                when (val result = repository.purchaseShopItem(itemId)) {
                    is ShopPurchaseResult.Success -> {
                        repository.currentProfile.value?.let { updated ->
                            _profile.value = updated
                            _profileState.value = ProfileUiState.Loaded(updated)
                        }
                        loadShopItems()
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.resource(R.string.profile_shop_purchase_success),
                            isError = false
                        )
                    }
                    ShopPurchaseResult.InsufficientHoney -> showShopError(R.string.profile_shop_insufficient_honey)
                    ShopPurchaseResult.AlreadyOwned -> {
                        loadShopItems()
                        showShopError(R.string.profile_shop_already_owned)
                    }
                    ShopPurchaseResult.ItemUnavailable -> {
                        loadShopItems()
                        showShopError(R.string.profile_shop_item_unavailable)
                    }
                    ShopPurchaseResult.Unauthenticated -> _profileEvent.value = ProfileEvent.LoggedOut
                    ShopPurchaseResult.Failure -> showShopError(R.string.profile_shop_purchase_failed)
                }
            } finally {
                _purchaseInFlight.value = false
            }
        }
    }

    fun equipCosmetic(itemId: String) {
        if (!_equipInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                when (val result = repository.equipCosmetic(itemId)) {
                    is ShopEquipResult.Success -> {
                        repository.currentProfile.value?.let { updated ->
                            _profile.value = updated
                            _profileState.value = ProfileUiState.Loaded(updated)
                        }
                        loadShopItems()
                        _profileEvent.value = ProfileEvent.ShowMessage(
                            UiText.resource(R.string.profile_shop_equipped_message),
                            isError = false
                        )
                    }
                    ShopEquipResult.NotOwned -> {
                        loadShopItems()
                        showShopError(R.string.profile_shop_not_owned)
                    }
                    ShopEquipResult.Unauthenticated -> _profileEvent.value = ProfileEvent.LoggedOut
                    ShopEquipResult.Failure -> showShopError(R.string.profile_shop_equip_failed)
                }
            } finally {
                _equipInFlight.value = false
            }
        }
    }

    private fun showShopError(messageRes: Int) {
        _profileEvent.value = ProfileEvent.ShowMessage(UiText.resource(messageRes), isError = true)
    }

    fun clearProfileEvent() {
        _profileEvent.value = null
    }

    private fun startProfileCollector() {
        profileCollectorJob?.cancel()
        profileCollectorJob = profileStateProvider.collectProfile(viewModelScope) { profile ->
            val displayProfile = profile?.let(::mergeWithOptimisticProfile)
            _profile.value = displayProfile
            if (displayProfile != null) {
                _profileState.value = ProfileUiState.Loaded(displayProfile)
            }
        }
    }

    private fun mergeWithOptimisticProfile(profile: Profile): Profile {
        val current = _profile.value ?: return profile
        if (current.id != profile.id) return profile

        val currentAvatar = current.avatar_url?.takeIf { it.isNotBlank() }
        return if (currentAvatar != null && profile.avatar_url.isNullOrBlank()) {
            profile.copy(avatar_url = currentAvatar)
        } else {
            profile
        }
    }
}
