package com.novahorizon.wanderly.ui.map

internal object MapLocationCallbackGuard {

    fun shouldHandleLocationUpdate(
        hasLocation: Boolean,
        isFragmentAdded: Boolean,
        hasBinding: Boolean
    ): Boolean {
        return hasLocation && isFragmentAdded && hasBinding
    }
}
