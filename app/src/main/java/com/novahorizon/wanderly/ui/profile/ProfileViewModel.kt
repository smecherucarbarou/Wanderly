package com.novahorizon.wanderly.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import kotlinx.coroutines.launch

class ProfileViewModel(private val repository: WanderlyRepository) : ViewModel() {
    private var hasCheckedBadgesThisSession = false

    fun loadProfile() {
        viewModelScope.launch {
            val profile = repository.getCurrentProfile() ?: return@launch
            if (!hasCheckedBadgesThisSession) {
                hasCheckedBadgesThisSession = true
                checkAndUnlockBadges(profile)
            }
        }
    }

    suspend fun checkAndUnlockBadges(profile: Profile) {
        val updatedProfile = ProfileBadgeEvaluator.updatedProfileWithUnlockedBadges(profile)
        val currentBadges = profile.badges?.toSet().orEmpty()
        val newBadges = updatedProfile.badges?.toSet().orEmpty()

        if (newBadges.size > currentBadges.size) {
            repository.updateProfile(updatedProfile)
        }
    }
}
