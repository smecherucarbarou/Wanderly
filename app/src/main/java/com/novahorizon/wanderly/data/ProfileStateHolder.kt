package com.novahorizon.wanderly.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Single owner of the in-memory current-profile state. Every read/write goes through here so all
 * mutations stay atomic (no read-modify-write races, audit H-3) and there is one source of truth as
 * ProfileRepository is carved into focused repositories (big_improvements A).
 *
 * The surface mirrors the [MutableStateFlow] members ProfileRepository already used, so call sites
 * did not change when ownership moved here.
 */
class ProfileStateHolder {
    private val state = MutableStateFlow<Profile?>(null)

    var value: Profile?
        get() = state.value
        set(newValue) {
            state.value = newValue
        }

    fun asStateFlow(): StateFlow<Profile?> = state.asStateFlow()

    /** Atomically applies [block] to the current value. */
    fun update(block: (Profile?) -> Profile?) {
        state.update(block)
    }

    /** Atomically applies [block] and returns the new value. */
    fun updateAndGet(block: (Profile?) -> Profile?): Profile? = state.updateAndGet(block)
}
