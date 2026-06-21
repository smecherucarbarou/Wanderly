package com.novahorizon.wanderly.ui.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

sealed class WanderlyRoute(
    val route: String,
    val label: String,
    val icon: ImageVector? = null
) {
    data object Splash : WanderlyRoute("splash", "Splash")
    data object Login : WanderlyRoute("login", "Login")
    data object Signup : WanderlyRoute("signup", "Signup")
    data object Onboarding : WanderlyRoute("onboarding", "Onboarding")
    data object Map : WanderlyRoute("map", "Map", Icons.Filled.Map)
    data object Gems : WanderlyRoute("gems", "Gems", Icons.Filled.Diamond)
    data object Missions : WanderlyRoute("missions", "Missions", Icons.Filled.Explore)
    data object Guide : WanderlyRoute("guide", "Guide", Icons.Filled.Explore)
    data object MissionDetail : WanderlyRoute("mission_detail", "Detail")
    data object PhotoVerification : WanderlyRoute("photo_verification", "Verify")
    data object Social : WanderlyRoute("social", "Hive", Icons.Filled.Groups)
    data object Profile : WanderlyRoute("profile", "Profile", Icons.Filled.Person)
    data object Settings : WanderlyRoute("settings", "Settings")
    data object DevDashboard : WanderlyRoute("dev_dashboard", "Dev")
}
