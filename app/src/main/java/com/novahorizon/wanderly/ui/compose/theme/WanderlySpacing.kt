package com.novahorizon.wanderly.ui.compose.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class WanderlySpacingValues(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val screenHorizontal: Dp = 20.dp,
    val cardPadding: Dp = 16.dp,
    val iconSmall: Dp = 16.dp,
    val iconMedium: Dp = 24.dp,
    val iconLarge: Dp = 48.dp,
    val minTouchTarget: Dp = 48.dp,
    val buttonHeight: Dp = 56.dp,
    val maxContentWidth: Dp = 520.dp
)

val LocalWanderlySpacing = staticCompositionLocalOf { WanderlySpacingValues() }

object WanderlySpacing {
    val current: WanderlySpacingValues
        @Composable
        get() = LocalWanderlySpacing.current
}
