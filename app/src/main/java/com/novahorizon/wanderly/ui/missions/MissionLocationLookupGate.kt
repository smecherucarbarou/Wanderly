package com.novahorizon.wanderly.ui.missions

internal class MissionLocationLookupGate {
    private var inFlight = false

    fun tryStart(): Boolean {
        if (inFlight) return false
        inFlight = true
        return true
    }

    fun finish() {
        inFlight = false
    }
}
