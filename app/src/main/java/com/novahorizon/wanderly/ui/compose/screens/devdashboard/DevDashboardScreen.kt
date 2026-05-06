package com.novahorizon.wanderly.ui.compose.screens.devdashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novahorizon.wanderly.R

data class DevDashboardCallbacks(
    val onUpdateStats: (honey: String, streak: String, flights: String) -> Unit,
    val onNotifyStreak: (streak: String) -> Unit,
    val onResetDailyCooldown: () -> Unit,
    val onNotifyEvening: () -> Unit,
    val onResetEveningCooldown: () -> Unit,
    val onNotifyMilestone: (streak: String) -> Unit,
    val onResetMilestoneCooldown: () -> Unit,
    val onNotifyLost: () -> Unit,
    val onResetLostCooldown: () -> Unit,
    val onNotifyRival: (friendName: String) -> Unit,
    val onResetRivalCooldown: () -> Unit,
    val onNotifyOvertaken: (friendName: String) -> Unit,
    val onResetOvertakenCooldown: () -> Unit,
    val onNotifyFight: (friendName: String) -> Unit,
    val onResetFightCooldown: () -> Unit,
    val onClearNotifCooldowns: () -> Unit,
    val onTestAiNotif: () -> Unit,
    val onRawLogs: () -> Unit,
    val onResetVisit: () -> Unit,
    val onReplayOnboarding: () -> Unit,
    val onRunWorkers: () -> Unit,
    val onCrashlyticsNonfatal: () -> Unit
)

@Composable
fun DevDashboardScreen(
    aiLogs: String,
    isAiRunning: Boolean,
    isCrashlyticsEnabled: Boolean,
    callbacks: DevDashboardCallbacks
) {
    var honeyText by rememberSaveable { mutableStateOf("") }
    var streakText by rememberSaveable { mutableStateOf("") }
    var flightsText by rememberSaveable { mutableStateOf("") }
    var friendNameText by rememberSaveable { mutableStateOf("") }

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

        // Stats card
        DevCard {
            Text(
                text = stringResource(R.string.dev_dashboard_modify_stats),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = honeyText,
                onValueChange = { honeyText = it },
                label = { Text(stringResource(R.string.dev_dashboard_honey_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = streakText,
                onValueChange = { streakText = it },
                label = { Text(stringResource(R.string.dev_dashboard_streak_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = flightsText,
                onValueChange = { flightsText = it },
                label = { Text(stringResource(R.string.dev_dashboard_flights_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            DevButton(
                text = stringResource(R.string.dev_dashboard_update_reality),
                onClick = { callbacks.onUpdateStats(honeyText, streakText, flightsText) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Notification sandbox card
        DevCard {
            Text(
                text = stringResource(R.string.dev_dashboard_notification_sandbox),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dev_dashboard_notification_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = friendNameText,
                onValueChange = { friendNameText = it },
                label = { Text(stringResource(R.string.dev_dashboard_target_rival_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            DevButton(text = stringResource(R.string.dev_dashboard_notify_streak), onClick = { callbacks.onNotifyStreak(streakText) })
            DevButton(text = stringResource(R.string.dev_dashboard_reset_daily_cooldown), onClick = callbacks.onResetDailyCooldown)
            DevButton(text = stringResource(R.string.dev_dashboard_notify_evening), onClick = callbacks.onNotifyEvening)
            DevButton(text = stringResource(R.string.dev_dashboard_reset_evening_cooldown), onClick = callbacks.onResetEveningCooldown)
            DevButton(text = stringResource(R.string.dev_dashboard_notify_milestone), onClick = { callbacks.onNotifyMilestone(streakText) })
            DevButton(text = stringResource(R.string.dev_dashboard_reset_milestone_cooldown), onClick = callbacks.onResetMilestoneCooldown)
            DevButton(text = stringResource(R.string.dev_dashboard_notify_lost), onClick = callbacks.onNotifyLost)
            DevButton(text = stringResource(R.string.dev_dashboard_reset_lost_cooldown), onClick = callbacks.onResetLostCooldown)
            DevButton(text = stringResource(R.string.dev_dashboard_notify_rival), onClick = { callbacks.onNotifyRival(friendNameText) })
            DevButton(text = stringResource(R.string.dev_dashboard_reset_rival_cooldown), onClick = callbacks.onResetRivalCooldown)
            DevButton(text = stringResource(R.string.dev_dashboard_notify_overtaken), onClick = { callbacks.onNotifyOvertaken(friendNameText) })
            DevButton(text = stringResource(R.string.dev_dashboard_reset_overtaken_cooldown), onClick = callbacks.onResetOvertakenCooldown)
            DevButton(text = stringResource(R.string.dev_dashboard_notify_fight), onClick = { callbacks.onNotifyFight(friendNameText) })
            DevButton(text = stringResource(R.string.dev_dashboard_reset_fight_cooldown), onClick = callbacks.onResetFightCooldown)
            Spacer(modifier = Modifier.height(4.dp))
            DevButton(
                text = stringResource(R.string.dev_dashboard_clear_notification_state),
                onClick = callbacks.onClearNotifCooldowns,
                isDestructive = true
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // AI preview card
        DevCard {
            Text(
                text = stringResource(R.string.dev_dashboard_ai_preview_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dev_dashboard_ai_preview_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            DevButton(
                text = stringResource(R.string.dev_dashboard_ai_generate),
                onClick = callbacks.onTestAiNotif,
                enabled = !isAiRunning
            )
            DevButton(
                text = stringResource(R.string.dev_dashboard_show_profile_json),
                onClick = callbacks.onRawLogs
            )
            if (aiLogs.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = aiLogs,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Background tools card
        DevCard {
            Text(
                text = stringResource(R.string.dev_dashboard_background_tools),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            DevButton(
                text = stringResource(R.string.dev_dashboard_reset_visit),
                onClick = callbacks.onResetVisit,
                isDestructive = true
            )
            DevButton(
                text = stringResource(R.string.dev_dashboard_replay_onboarding),
                onClick = callbacks.onReplayOnboarding
            )
            DevButton(
                text = stringResource(R.string.dev_dashboard_force_run_workers),
                onClick = callbacks.onRunWorkers
            )
            DevButton(
                text = stringResource(R.string.dev_dashboard_send_crashlytics_nonfatal),
                onClick = callbacks.onCrashlyticsNonfatal,
                enabled = isCrashlyticsEnabled
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DevCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
