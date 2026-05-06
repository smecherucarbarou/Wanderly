package com.novahorizon.wanderly.ui.compose.screens.missions

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.asFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.ui.compose.components.BuzzyMascot
import com.novahorizon.wanderly.ui.compose.components.HoneyButton
import com.novahorizon.wanderly.ui.compose.components.HoneyCounter
import com.novahorizon.wanderly.ui.compose.components.MissionCard
import com.novahorizon.wanderly.ui.compose.components.WanderlyCard
import com.novahorizon.wanderly.ui.compose.util.rankDisplayName
import com.novahorizon.wanderly.ui.compose.util.rankProgress
import com.novahorizon.wanderly.ui.compose.util.uiTextToString
import com.novahorizon.wanderly.ui.missions.MissionsViewModel
import com.novahorizon.wanderly.ui.missions.MissionsViewModel.MissionState

@Composable
fun MissionsScreen(
    viewModel: MissionsViewModel,
    onGenerateMission: () -> Unit,
    onVerifyPhoto: () -> Unit,
    onCompleteMission: () -> Unit,
    onLearnMore: () -> Unit
) {
    val profile by viewModel.profile.asFlow().collectAsStateWithLifecycle(null)
    val missionState by viewModel.missionState.asFlow().collectAsStateWithLifecycle(MissionState.Idle)
    val streakMessage by viewModel.streakMessage.asFlow().collectAsStateWithLifecycle(null)
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        val honey = profile?.honey ?: 0
        val rankInt = HiveRank.fromHoney(honey)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HoneyCounter(count = honey)
            Text(
                text = rankDisplayName(rankInt),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { rankProgress(honey, rankInt) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.Top) {
            BuzzyMascot(size = 50.dp)
            WanderlyCard(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
                elevation = 1.dp
            ) {
                Text(
                    text = streakMessage ?: "Your next mission awaits! Tap below to explore.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        when (val state = missionState) {
            is MissionState.Idle -> {
                MissionCard(
                    title = "No Active Mission",
                    description = "Generate an AI mission to explore interesting places near you.",
                    stateLabel = "Ready",
                    primaryAction = {
                        HoneyButton(text = "Generate New Mission", onClick = onGenerateMission)
                    }
                )
            }
            is MissionState.Generating -> {
                MissionCard(
                    title = "Finding a place for you...",
                    description = "AI is generating a personalized exploration mission.",
                    stateLabel = "Generating"
                )
            }
            is MissionState.MissionReceived -> {
                MissionCard(
                    title = viewModel.currentMissionText() ?: "Mission Ready!",
                    description = "Navigate to this location and verify your visit with a photo.",
                    stateLabel = "Active Mission",
                    primaryAction = {
                        HoneyButton(text = "Verify with Photo", onClick = onVerifyPhoto)
                    },
                    secondaryAction = {
                        OutlinedButton(
                            onClick = onLearnMore,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Learn More")
                        }
                    }
                )
            }
            is MissionState.Verifying -> {
                MissionCard(
                    title = "Verifying your photo...",
                    description = "AI is analyzing your photo to confirm the visit.",
                    stateLabel = "Verifying"
                )
            }
            is MissionState.VerificationResult -> {
                if (state.success) {
                    MissionCard(
                        title = "Verified!",
                        description = "Location confirmed. Complete the mission for your reward!",
                        stateLabel = "Complete",
                        primaryAction = {
                            HoneyButton(text = "Complete Mission", onClick = onCompleteMission)
                        }
                    )
                } else {
                    MissionCard(
                        title = "Not quite right",
                        description = uiTextToString(state.message, context),
                        stateLabel = "Retry",
                        primaryAction = {
                            HoneyButton(text = "Try Again", onClick = onVerifyPhoto)
                        }
                    )
                }
            }
            is MissionState.Completing -> {
                MissionCard(
                    title = "Completing...",
                    description = "Recording your exploration achievement.",
                    stateLabel = "Completing"
                )
            }
            is MissionState.Error -> {
                MissionCard(
                    title = "Error",
                    description = uiTextToString(state.message, context),
                    stateLabel = "Error",
                    primaryAction = {
                        HoneyButton(text = "Retry", onClick = onGenerateMission)
                    }
                )
            }
            else -> {}
        }
    }
}
