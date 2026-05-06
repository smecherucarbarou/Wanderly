package com.novahorizon.wanderly.ui.compose.screens.home

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
import com.novahorizon.wanderly.data.Profile
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
fun HomeScreen(
    viewModel: MissionsViewModel,
    onGenerateMission: () -> Unit,
    onVerifyPhoto: () -> Unit,
    onCompleteMission: () -> Unit
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
                    text = streakMessage
                        ?: "Ready for a new adventure? There are hidden gems waiting nearby!",
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
                    description = "Tap below to generate an AI-powered exploration mission near you.",
                    stateLabel = "Ready to Explore",
                    primaryAction = {
                        HoneyButton(text = "Generate New Mission", onClick = onGenerateMission)
                    }
                )
            }
            is MissionState.Generating -> {
                MissionCard(
                    title = "Generating Mission...",
                    description = "Our AI is finding an interesting place for you to explore nearby.",
                    stateLabel = "Generating"
                )
            }
            is MissionState.MissionReceived -> {
                MissionCard(
                    title = viewModel.currentMissionText() ?: "Mission Ready",
                    description = "Head to this location and take a photo to verify your visit.",
                    stateLabel = "Mission Active",
                    primaryAction = {
                        HoneyButton(text = "Verify with Photo", onClick = onVerifyPhoto)
                    }
                )
            }
            is MissionState.Verifying -> {
                MissionCard(
                    title = "Verifying Photo...",
                    description = "Our AI is checking your photo to confirm you visited the location.",
                    stateLabel = "Verifying"
                )
            }
            is MissionState.VerificationResult -> {
                if (state.success) {
                    MissionCard(
                        title = "Photo Verified!",
                        description = "Your visit has been confirmed. Complete the mission to earn honey!",
                        stateLabel = "Verified",
                        primaryAction = {
                            HoneyButton(text = "Complete Mission", onClick = onCompleteMission)
                        }
                    )
                } else {
                    MissionCard(
                        title = "Verification Failed",
                        description = uiTextToString(state.message, context),
                        stateLabel = "Try Again",
                        primaryAction = {
                            HoneyButton(text = "Try Again", onClick = onVerifyPhoto)
                        }
                    )
                }
            }
            is MissionState.Completing -> {
                MissionCard(
                    title = "Completing Mission...",
                    description = "Recording your achievement...",
                    stateLabel = "Completing"
                )
            }
            is MissionState.Error -> {
                MissionCard(
                    title = "Something went wrong",
                    description = uiTextToString(state.message, context),
                    stateLabel = "Error",
                    primaryAction = {
                        HoneyButton(text = "Try Again", onClick = onGenerateMission)
                    }
                )
            }
            else -> {}
        }
    }
}
