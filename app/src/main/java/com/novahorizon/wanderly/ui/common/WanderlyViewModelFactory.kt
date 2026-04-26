package com.novahorizon.wanderly.ui.common

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.auth.AuthViewModel
import com.novahorizon.wanderly.ui.gems.GemsViewModel
import com.novahorizon.wanderly.ui.main.MainViewModel
import com.novahorizon.wanderly.ui.map.MapViewModel
import com.novahorizon.wanderly.ui.missions.MissionsViewModel
import com.novahorizon.wanderly.ui.profile.AdminToolsViewModel
import com.novahorizon.wanderly.ui.profile.ProfileViewModel
import com.novahorizon.wanderly.ui.social.SocialViewModel

class WanderlyViewModelFactory(private val repository: WanderlyRepository) : ViewModelProvider.Factory {
    private val profileStateProvider = ProfileStateProvider(repository)

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return create(modelClass, SavedStateHandle())
    }

    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        return create(modelClass, extras.createSavedStateHandle())
    }

    private fun <T : ViewModel> create(modelClass: Class<T>, savedStateHandle: SavedStateHandle): T {
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
             return MissionsViewModel(
                 repository,
                 savedStateHandle,
                 profileStateProvider,
                 WanderlyGraph.missionDetailsRepository()
             ) as T
        }
        if (modelClass.isAssignableFrom(MapViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MapViewModel(repository) as T
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
            return ProfileViewModel(repository, profileStateProvider) as T
        }
        if (modelClass.isAssignableFrom(AdminToolsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AdminToolsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
