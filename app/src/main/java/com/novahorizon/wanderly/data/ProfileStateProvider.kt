package com.novahorizon.wanderly.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ProfileStateProvider(private val repository: WanderlyRepository) {
    fun collectProfile(scope: CoroutineScope, onProfile: (Profile?) -> Unit): Job {
        return scope.launch {
            repository.currentProfile.collectLatest { profile ->
                onProfile(profile)
            }
        }
    }

    suspend fun refreshProfile(): Profile? = repository.getCurrentProfile()
}
