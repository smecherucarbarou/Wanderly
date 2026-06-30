package com.novahorizon.wanderly.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationDestinationsTest {

    @Test
    fun `starts on onboarding when onboarding is unseen`() {
        assertTrue(MainNavigationDestinations.startsOnOnboarding(onboardingSeen = false))
    }

    @Test
    fun `starts on map when onboarding is already seen`() {
        assertFalse(MainNavigationDestinations.startsOnOnboarding(onboardingSeen = true))
    }

    @Test
    fun `shouldShowBottomNavigation hides bottom nav on onboarding`() {
        assertFalse(MainNavigationDestinations.shouldShowBottomNavigation(isOnboardingDestination = true))
    }

    @Test
    fun `shouldShowBottomNavigation shows bottom nav off onboarding`() {
        assertTrue(MainNavigationDestinations.shouldShowBottomNavigation(isOnboardingDestination = false))
    }
}
