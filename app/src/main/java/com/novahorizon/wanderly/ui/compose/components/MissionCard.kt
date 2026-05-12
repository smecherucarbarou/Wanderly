package com.novahorizon.wanderly.ui.compose.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme

@Composable
fun MissionCard(
    title: String,
    description: String,
    stateLabel: String,
    modifier: Modifier = Modifier,
    primaryAction: (@Composable () -> Unit)? = null,
    secondaryAction: (@Composable () -> Unit)? = null
) {
    val spacing = WanderlyTheme.spacing
    WanderlyCard(modifier = modifier, elevation = spacing.xs) {
        StatusChip(label = stateLabel)
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (primaryAction != null) {
            Spacer(modifier = Modifier.height(spacing.xl))
            primaryAction()
        }
        if (secondaryAction != null) {
            Spacer(modifier = Modifier.height(spacing.md))
            secondaryAction()
        }
    }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewMissionCard() {
    WanderlyTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MissionCard(
                title = "Visit the Old Town Square",
                description = "Explore the historic center and take a photo near the fountain.",
                stateLabel = "In Progress"
            )
            MissionCard(
                title = "Sunset at the Peak",
                description = "Hike to the summit and capture the golden hour view.",
                stateLabel = "Completed",
                primaryAction = {
                    HoneyButton(text = "Claim Reward", onClick = {})
                }
            )
        }
    }
}
