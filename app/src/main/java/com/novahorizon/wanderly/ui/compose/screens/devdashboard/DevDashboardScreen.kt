package com.novahorizon.wanderly.ui.compose.screens.devdashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novahorizon.wanderly.R

data class DevDashboardRow(
    val label: String,
    val value: String
)

data class DevDashboardSection(
    val title: String,
    val rows: List<DevDashboardRow> = emptyList()
)

data class DevDashboardDiagnostics(
    val sections: List<DevDashboardSection> = emptyList()
)

data class DevDashboardCallbacks(
    val onRefreshDiagnostics: () -> Unit,
    val onOpenNotificationSettings: () -> Unit,
    val onClearNotificationState: () -> Unit,
    val onCrashlyticsNonfatal: () -> Unit
)

@Composable
fun DevDashboardScreen(
    diagnostics: DevDashboardDiagnostics,
    isCrashlyticsEnabled: Boolean,
    callbacks: DevDashboardCallbacks
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.dev_dashboard_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.dev_dashboard_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.dev_dashboard_diagnostics_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))

        diagnostics.sections.forEach { section ->
            DevCard(title = section.title) {
                section.rows.forEach { row ->
                    DiagnosticRow(row)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        DevCard(title = stringResource(R.string.dev_dashboard_build_app_tools)) {
            DevButton(
                text = stringResource(R.string.dev_dashboard_refresh_diagnostics),
                onClick = callbacks.onRefreshDiagnostics
            )
            DevButton(
                text = stringResource(R.string.dev_dashboard_send_crashlytics_nonfatal),
                onClick = callbacks.onCrashlyticsNonfatal,
                enabled = isCrashlyticsEnabled
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        DevCard(title = stringResource(R.string.dev_dashboard_streak_notifications_tools)) {
            DevButton(
                text = stringResource(R.string.dev_dashboard_open_notification_settings),
                onClick = callbacks.onOpenNotificationSettings
            )
            DevButton(
                text = stringResource(R.string.dev_dashboard_clear_notification_state),
                onClick = callbacks.onClearNotificationState,
                isDestructive = true
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DiagnosticRow(row: DevDashboardRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = row.value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.2f)
        )
    }
}

@Composable
private fun DevCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DevButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = if (isDestructive) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        }
    ) {
        Text(text = text)
    }
}
