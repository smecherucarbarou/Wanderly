package com.novahorizon.wanderly.ui.gems

internal object GemsLocationCallbackGuard {

    fun shouldHandleLocationSuccess(
        hasLocation: Boolean,
        isFragmentAdded: Boolean,
        hasBinding: Boolean,
        hasLifecycleOwner: Boolean
    ): Boolean {
        return hasLocation && isFragmentAdded && hasBinding && hasLifecycleOwner
    }

    fun shouldHandleLocationFailure(
        isFragmentAdded: Boolean,
        hasBinding: Boolean
    ): Boolean {
        return isFragmentAdded && hasBinding
    }
}
