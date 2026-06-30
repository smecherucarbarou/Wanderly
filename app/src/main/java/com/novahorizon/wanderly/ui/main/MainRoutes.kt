package com.novahorizon.wanderly.ui.main

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation-Compose routes for the main graph (hosted by [com.novahorizon.wanderly.MainActivity]).
 * Part of big-improvements B: the Compose NavHost replaces the Fragment + XML nav-graph host layer.
 * During the interop phase each destination renders its existing Fragment via `AndroidFragment`.
 */
@Serializable
data object OnboardingRoute

@Serializable
data object MapRoute

@Serializable
data object GemsRoute

@Serializable
data object MissionsRoute

@Serializable
data object SocialRoute

@Serializable
data object ProfileRoute

@Serializable
data object GuideRoute

@Serializable
data object DevDashboardRoute
