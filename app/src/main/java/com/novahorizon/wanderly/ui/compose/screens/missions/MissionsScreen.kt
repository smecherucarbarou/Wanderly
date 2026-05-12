package com.novahorizon.wanderly.ui.compose.screens.missions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.ui.compose.components.BuzzyMascot
import com.novahorizon.wanderly.ui.compose.components.HoneyButton
import com.novahorizon.wanderly.ui.compose.components.HoneyCounter
import com.novahorizon.wanderly.ui.compose.components.MissionCard
import com.novahorizon.wanderly.ui.compose.components.StreakPill
import com.novahorizon.wanderly.ui.compose.components.WanderlyCard
import com.novahorizon.wanderly.ui.compose.components.WanderlyEmptyState
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
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
    val spacing = WanderlyTheme.spacing

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = spacing.maxContentWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.lg)
        ) {
        val honey = profile?.honey ?: 0
        val rankInt = HiveRank.fromHoney(honey)

        Text(
            text = stringResource(R.string.missions_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = stringResource(R.string.missions_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(spacing.lg))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HoneyCounter(count = honey)
            StreakPill(count = profile?.streak_count ?: 0)
            Text(
                text = rankDisplayName(rankInt),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(spacing.sm))

        LinearProgressIndicator(
            progress = { rankProgress(honey, rankInt) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        )

        Spacer(modifier = Modifier.height(spacing.xl))

        Row(verticalAlignment = Alignment.Top) {
            BuzzyMascot(size = 50.dp)
            WanderlyCard(
                modifier = Modifier
                    .padding(start = spacing.md)
                    .weight(1f),
                elevation = 1.dp
            ) {
                Text(
                    text = streakMessage ?: stringResource(R.string.mission_streak_default),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.xl))

        when (val state = missionState) {
            is MissionState.Idle -> {
                WanderlyEmptyState(
                    title = stringResource(R.string.mission_idle_title),
                    message = stringResource(R.string.mission_idle_description),
                    actionLabel = stringResource(R.string.mission_generate_button),
                    onAction = onGenerateMission
                )
            }
            is MissionState.Generating -> {
                MissionCard(
                    title = stringResource(R.string.mission_generating_title),
                    description = stringResource(R.string.mission_generating_description),
                    stateLabel = stringResource(R.string.mission_generating_label)
                )
            }
            is MissionState.MissionReceived -> {
                MissionCard(
                    title = viewModel.currentMissionText() ?: stringResource(R.string.mission_ready_fallback),
                    description = stringResource(R.string.mission_received_description),
                    stateLabel = stringResource(R.string.mission_received_label),
                    primaryAction = {
                        HoneyButton(text = stringResource(R.string.mission_verify_button), onClick = onVerifyPhoto)
                    },
                    secondaryAction = {
                        OutlinedButton(
                            onClick = onLearnMore,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(stringResource(R.string.mission_learn_more))
                        }
                    }
                )
            }
            is MissionState.Verifying -> {
                MissionCard(
                    title = stringResource(R.string.mission_verifying_title),
                    description = stringResource(R.string.mission_verifying_description),
                    stateLabel = stringResource(R.string.mission_verifying_label)
                )
            }
            is MissionState.VerificationResult -> {
                if (state.success) {
                    MissionCard(
                        title = stringResource(R.string.mission_verified_title),
                        description = stringResource(R.string.mission_verified_description),
                        stateLabel = stringResource(R.string.mission_verified_label),
                        primaryAction = {
                            HoneyButton(text = stringResource(R.string.mission_complete_button), onClick = onCompleteMission)
                        }
                    )
                } else {
                    MissionCard(
                        title = stringResource(R.string.mission_failed_title),
                        description = uiTextToString(state.message, context),
                        stateLabel = stringResource(R.string.mission_failed_label),
                        primaryAction = {
                            HoneyButton(text = stringResource(R.string.mission_try_again), onClick = onVerifyPhoto)
                        }
                    )
                }
            }
            is MissionState.Completing -> {
                MissionCard(
                    title = stringResource(R.string.mission_completing_title),
                    description = stringResource(R.string.mission_completing_description),
                    stateLabel = stringResource(R.string.mission_completing_label)
                )
            }
            is MissionState.Error -> {
                MissionCard(
                    title = stringResource(R.string.mission_error_title),
                    description = uiTextToString(state.message, context),
                    stateLabel = stringResource(R.string.mission_error_label),
                    primaryAction = {
                        HoneyButton(text = stringResource(R.string.common_retry), onClick = onGenerateMission)
                    }
                )
            }
            else -> {}
        }
        }
    }
}
