package com.novahorizon.wanderly.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.auth.AuthViewModel
import com.novahorizon.wanderly.ui.gems.GemsViewModel
import com.novahorizon.wanderly.ui.main.MainViewModel
import com.novahorizon.wanderly.ui.missions.MissionsViewModel
import com.novahorizon.wanderly.ui.profile.ProfileViewModel
import com.novahorizon.wanderly.ui.social.SocialViewModel

class WanderlyViewModelFactory(private val repository: WanderlyRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AuthViewModel() as T
        }
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(MissionsViewModel::class.java)) {
             @Suppress("UNCHECKED_CAST")
             return MissionsViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(SocialViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SocialViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(GemsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GemsViewModel(repository) as T
        }
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
