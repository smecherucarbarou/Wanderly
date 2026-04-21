package com.novahorizon.wanderly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationDestinationsTest {

    @Test
    fun `initialStartDestination returns onboarding when onboarding is unseen`() {
        val destination = MainNavigationDestinations.initialStartDestination(
            onboardingSeen = false,
            mapDestinationId = 10,
            onboardingDestinationId = 20
        )

        assertEquals(20, destination)
    }

    @Test
    fun `initialStartDestination returns map when onboarding is already seen`() {
        val destination = MainNavigationDestinations.initialStartDestination(
            onboardingSeen = true,
            mapDestinationId = 10,
            onboardingDestinationId = 20
        )

        assertEquals(10, destination)
    }

    @Test
    fun `destinationAfterOnboarding always resets to map`() {
        val destination = MainNavigationDestinations.destinationAfterOnboarding(mapDestinationId = 10)

        assertEquals(10, destination)
    }

    @Test
    fun `shouldShowBottomNavigation hides bottom nav on onboarding`() {
        assertFalse(
            MainNavigationDestinations.shouldShowBottomNavigation(
                currentDestinationId = 20,
                onboardingDestinationId = 20
            )
        )
    }

    @Test
    fun `shouldShowBottomNavigation shows bottom nav off onboarding`() {
        assertTrue(
            MainNavigationDestinations.shouldShowBottomNavigation(
                currentDestinationId = 10,
                onboardingDestinationId = 20
            )
        )
    }
}
