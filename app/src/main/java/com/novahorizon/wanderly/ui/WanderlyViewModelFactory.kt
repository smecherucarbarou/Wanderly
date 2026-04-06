package com.novahorizon.wanderly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.novahorizon.wanderly.data.WanderlyRepository

class WanderlyViewModelFactory(private val repository: WanderlyRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AuthViewModel(repository) as T
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
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
