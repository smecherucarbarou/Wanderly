package com.novahorizon.wanderly.ui.compose.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.novahorizon.wanderly.ui.compose.WanderlyRoute
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme

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
        modifier = modifier.navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        items.forEach { route ->
            NavigationBarItem(
                selected = currentRoute == route,
                onClick = { onRouteSelected(route) },
                icon = {
                    route.icon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = route.label
                        )
                    }
                },
                label = {
                    Text(
                        text = route.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewWanderlyBottomBar() {
    WanderlyTheme {
        WanderlyBottomBar(
            currentRoute = WanderlyRoute.Missions,
            onRouteSelected = {}
        )
    }
}
