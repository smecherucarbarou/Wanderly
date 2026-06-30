package com.novahorizon.wanderly.ui.auth

import kotlinx.serialization.Serializable

/**
 * Type-safe Navigation-Compose routes for the auth graph (hosted by [com.novahorizon.wanderly.AuthActivity]).
 * Part of big-improvements B: replacing the Fragment + XML nav-graph host layer with a Compose NavHost.
 */
@Serializable
data object LoginRoute

@Serializable
data object SignupRoute
