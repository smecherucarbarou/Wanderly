package com.novahorizon.wanderly.ui.compose.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.novahorizon.wanderly.ui.compose.WanderlyRoute

@Composable
fun WanderlyBottomBar(
    currentRoute: WanderlyRoute,
    onRouteSelected: (WanderlyRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        WanderlyRoute.Map,
        WanderlyRoute.Gems,
        WanderlyRoute.Missions,
        WanderlyRoute.Social,
        WanderlyRoute.Profile
    )

    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        items.forEach { route ->
            NavigationBarItem(
                selected = currentRoute == route,
                onClick = { onRouteSelected(route) },
                icon = {
                    Text(text = route.icon)
                },
                label = {
                    Text(text = route.label)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                )
            )
        }
    }
}
