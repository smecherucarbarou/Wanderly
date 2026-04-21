package com.novahorizon.wanderly.ui

internal object MainNavigationDestinations {

    fun initialStartDestination(
        onboardingSeen: Boolean,
        mapDestinationId: Int,
        onboardingDestinationId: Int
    ): Int {
        return if (onboardingSeen) mapDestinationId else onboardingDestinationId
    }

    fun destinationAfterOnboarding(mapDestinationId: Int): Int = mapDestinationId

    fun shouldShowBottomNavigation(
        currentDestinationId: Int,
        onboardingDestinationId: Int
    ): Boolean {
        return currentDestinationId != onboardingDestinationId
    }
}
