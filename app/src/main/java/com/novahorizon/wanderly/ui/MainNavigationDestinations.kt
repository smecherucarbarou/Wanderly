package com.novahorizon.wanderly.ui

/**
 * Pure navigation rules for the main graph, kept framework-free for unit testing.
 * Consumed by the Compose NavHost in [com.novahorizon.wanderly.MainActivity].
 */
internal object MainNavigationDestinations {

    /** First destination depends on whether the user has completed onboarding. */
    fun startsOnOnboarding(onboardingSeen: Boolean): Boolean = !onboardingSeen

    /** Bottom navigation is hidden only while onboarding is on screen. */
    fun shouldShowBottomNavigation(isOnboardingDestination: Boolean): Boolean = !isOnboardingDestination
}
