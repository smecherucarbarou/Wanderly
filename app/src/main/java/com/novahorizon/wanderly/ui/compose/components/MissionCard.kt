package com.novahorizon.wanderly.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun MissionCard(
    title: String,
    description: String,
    stateLabel: String,
    modifier: Modifier = Modifier,
    primaryAction: (@Composable () -> Unit)? = null,
    secondaryAction: (@Composable () -> Unit)? = null
) {
    WanderlyCard(modifier = modifier, elevation = 4.dp) {
        StatusChip(label = stateLabel)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (primaryAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            primaryAction()
        }
        if (secondaryAction != null) {
            Spacer(modifier = Modifier.height(12.dp))
            secondaryAction()
        }
    }
}
