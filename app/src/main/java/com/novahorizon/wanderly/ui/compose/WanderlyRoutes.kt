package com.novahorizon.wanderly.ui.compose

sealed class WanderlyRoute(
    val route: String,
    val label: String,
    val icon: String
) {
    data object Splash : WanderlyRoute("splash", "Splash", "")
    data object Login : WanderlyRoute("login", "Login", "")
    data object Signup : WanderlyRoute("signup", "Signup", "")
    data object Onboarding : WanderlyRoute("onboarding", "Onboarding", "")
    data object Map : WanderlyRoute("map", "Map", "🗺️")
    data object Gems : WanderlyRoute("gems", "Gems", "💎")
    data object Missions : WanderlyRoute("missions", "Missions", "🐝")
    data object MissionDetail : WanderlyRoute("mission_detail", "Detail", "")
    data object PhotoVerification : WanderlyRoute("photo_verification", "Verify", "")
    data object Social : WanderlyRoute("social", "Hive", "👥")
    data object Profile : WanderlyRoute("profile", "Profile", "👤")
    data object Settings : WanderlyRoute("settings", "Settings", "")
    data object DevDashboard : WanderlyRoute("dev_dashboard", "Dev", "")
}
