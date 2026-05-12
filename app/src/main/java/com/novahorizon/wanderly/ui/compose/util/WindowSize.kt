package com.novahorizon.wanderly.ui.compose.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

enum class WanderlyWindowSize {
    Compact,   // phones portrait (< 600dp)
    Medium,    // phones landscape / small foldables (600-839dp)
    Expanded   // tablets (840dp+)
}

@Composable
fun rememberWindowSize(): WanderlyWindowSize {
    val configuration = LocalConfiguration.current
    return when {
        configuration.screenWidthDp < 600 -> WanderlyWindowSize.Compact
        configuration.screenWidthDp < 840 -> WanderlyWindowSize.Medium
        else -> WanderlyWindowSize.Expanded
    }
}
