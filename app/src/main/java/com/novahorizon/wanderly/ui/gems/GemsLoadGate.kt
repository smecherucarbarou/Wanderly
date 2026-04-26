package com.novahorizon.wanderly.ui.gems

object GemsLoadGate {
    internal fun shouldAutoLoad(currentState: GemsViewModel.GemsState?): Boolean {
        return currentState == null || currentState is GemsViewModel.GemsState.Idle
    }
}
