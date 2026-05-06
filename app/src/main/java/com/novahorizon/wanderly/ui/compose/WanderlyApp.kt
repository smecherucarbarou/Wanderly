package com.novahorizon.wanderly.ui.compose

import androidx.compose.runtime.Composable
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme

@Composable
fun WanderlyApp(
    content: @Composable () -> Unit
) {
    WanderlyTheme {
        content()
    }
}
