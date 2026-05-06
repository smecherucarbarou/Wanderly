package com.novahorizon.wanderly.ui.compose.screens.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.asFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.novahorizon.wanderly.data.Gem
import com.novahorizon.wanderly.ui.compose.components.ErrorState
import com.novahorizon.wanderly.ui.compose.components.HoneyButton
import com.novahorizon.wanderly.ui.compose.components.LoadingState
import com.novahorizon.wanderly.ui.compose.components.WanderlyCard
import com.novahorizon.wanderly.ui.compose.util.uiTextToString
import com.novahorizon.wanderly.ui.gems.GemsViewModel
import com.novahorizon.wanderly.ui.gems.GemsViewModel.GemsState

@Composable
fun GemsScreen(
    viewModel: GemsViewModel,
    onRetry: () -> Unit,
    onGemClick: (Gem) -> Unit
) {
    val gemsState by viewModel.gemsState.asFlow().collectAsStateWithLifecycle(GemsState.Idle)
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 20.dp)
    ) {
        Text(
            text = "Hidden Gems",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        when (val state = gemsState) {
            is GemsState.Loading -> {
                LoadingState(message = uiTextToString(state.message, context))
            }
            is GemsState.Empty -> {
                ErrorState(
                    message = uiTextToString(state.message, context),
                    onRetry = onRetry
                )
            }
            is GemsState.Error -> {
                ErrorState(
                    message = uiTextToString(state.message, context),
                    onRetry = onRetry
                )
            }
            is GemsState.Loaded -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.gems, key = { it.name }) { gem ->
                        GemCard(
                            name = gem.name,
                            location = gem.location,
                            description = gem.description,
                            reason = gem.reason,
                            onClick = { onGemClick(gem) }
                        )
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Discover hidden gems near your location.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    HoneyButton(text = "Find Gems", onClick = onRetry)
                }
            }
        }
    }
}

@Composable
private fun GemCard(
    name: String,
    location: String,
    description: String,
    reason: String,
    onClick: () -> Unit
) {
    WanderlyCard(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        if (location.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "📍 $location",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (description.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (reason.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }
    }
}
