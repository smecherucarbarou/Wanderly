package com.novahorizon.wanderly.ui.compose.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = WanderlyColors.Primary,
    onPrimary = WanderlyColors.Secondary,
    primaryContainer = WanderlyColors.Accent,
    onPrimaryContainer = WanderlyColors.Secondary,
    secondary = WanderlyColors.Secondary,
    onSecondary = Color.White,
    secondaryContainer = WanderlyColors.Accent,
    onSecondaryContainer = WanderlyColors.Secondary,
    tertiary = WanderlyColors.Accent,
    onTertiary = WanderlyColors.Secondary,
    background = WanderlyColors.Background,
    onBackground = WanderlyColors.TextPrimary,
    surface = WanderlyColors.CardBackground,
    onSurface = WanderlyColors.TextPrimary,
    surfaceVariant = WanderlyColors.PollenWhite,
    onSurfaceVariant = WanderlyColors.TextSecondary,
    error = WanderlyColors.Error,
    onError = Color.White,
    outline = WanderlyColors.Primary.copy(alpha = 0.3f)
)

private val DarkColorScheme = darkColorScheme(
    primary = WanderlyColors.DarkPrimary,
    onPrimary = WanderlyColors.DarkBackground,
    primaryContainer = WanderlyColors.DarkAccent,
    onPrimaryContainer = WanderlyColors.DarkBackground,
    secondary = WanderlyColors.DarkSecondary,
    onSecondary = WanderlyColors.DarkBackground,
    secondaryContainer = WanderlyColors.DarkAccent,
    onSecondaryContainer = WanderlyColors.DarkBackground,
    tertiary = WanderlyColors.DarkAccent,
    onTertiary = WanderlyColors.DarkBackground,
    background = WanderlyColors.DarkBackground,
    onBackground = WanderlyColors.DarkTextPrimary,
    surface = WanderlyColors.DarkCardBackground,
    onSurface = WanderlyColors.DarkTextPrimary,
    surfaceVariant = WanderlyColors.DarkCardBackground,
    onSurfaceVariant = WanderlyColors.DarkTextSecondary,
    error = WanderlyColors.Error,
    onError = Color.White,
    outline = WanderlyColors.DarkTextSecondary
)

data class WanderlyExtendedColors(
    val gradientStart: Color,
    val gradientEnd: Color,
    val honey: Color,
    val streak: Color,
    val cardBorder: Color
)

val LocalWanderlyColors = staticCompositionLocalOf {
    WanderlyExtendedColors(
        gradientStart = WanderlyColors.GradientStart,
        gradientEnd = WanderlyColors.GradientEnd,
        honey = WanderlyColors.Primary,
        streak = WanderlyColors.Accent,
        cardBorder = WanderlyColors.Primary.copy(alpha = 0.15f)
    )
}

@Composable
fun WanderlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val extendedColors = if (darkTheme) {
        WanderlyExtendedColors(
            gradientStart = WanderlyColors.DarkGradientStart,
            gradientEnd = WanderlyColors.DarkGradientEnd,
            honey = WanderlyColors.DarkPrimary,
            streak = WanderlyColors.DarkAccent,
            cardBorder = WanderlyColors.DarkPrimary.copy(alpha = 0.35f)
        )
    } else {
        WanderlyExtendedColors(
            gradientStart = WanderlyColors.GradientStart,
            gradientEnd = WanderlyColors.GradientEnd,
            honey = WanderlyColors.Primary,
            streak = WanderlyColors.Accent,
            cardBorder = WanderlyColors.Primary.copy(alpha = 0.15f)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalWanderlyColors provides extendedColors,
        LocalWanderlySpacing provides WanderlySpacingValues()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WanderlyTypography,
            shapes = WanderlyShapes,
            content = content
        )
    }
}

object WanderlyTheme {
    val extendedColors: WanderlyExtendedColors
        @Composable
        get() = LocalWanderlyColors.current

    val spacing: WanderlySpacingValues
        @Composable
        get() = LocalWanderlySpacing.current
}
