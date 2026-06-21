package com.novahorizon.wanderly.ui.compose.screens.profile

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.ui.compose.components.ErrorState
import com.novahorizon.wanderly.ui.compose.components.LoadingState
import com.novahorizon.wanderly.ui.compose.components.WanderlyEmptyState
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.streak.DailyStreakStatus
import com.novahorizon.wanderly.streak.DailyStreakStatusEvaluator
import com.novahorizon.wanderly.ui.profile.ProfileViewModel
import com.novahorizon.wanderly.ui.profile.ProfileViewModel.ProfileUiState
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    avatarDisplaySource: String? = null,
    showDevPanel: Boolean = false,
    notificationStatusText: String? = null,
    notificationActionLabel: String? = null,
    onLogout: () -> Unit,
    onSettings: () -> Unit,
    onNotificationAction: () -> Unit = {},
    onEditAvatar: () -> Unit,
    onEditUsername: () -> Unit = {},
    onCopyFriendCode: (String) -> Unit = {},
    onShareFriendCode: (String) -> Unit = {},
    onUseStreakFreeze: () -> Unit = {},
    onRetry: () -> Unit = { viewModel.loadProfile() }
) {
    val profileState by viewModel.profileState.asFlow().collectAsStateWithLifecycle(ProfileUiState.Loading)
    val avatarUploadState by viewModel.avatarUploadState.asFlow()
        .collectAsStateWithLifecycle(ProfileViewModel.AvatarUploadState.Idle)

    when (profileState) {
        is ProfileUiState.Loading -> {
            LoadingState(message = stringResource(R.string.profile_loading))
        }
        is ProfileUiState.Error -> {
            ErrorState(
                message = stringResource(R.string.profile_load_failed),
                onRetry = onRetry
            )
        }
        ProfileUiState.Empty -> {
            EmptyProfileState(onRetry = onRetry)
        }
        is ProfileUiState.Loaded -> {
            ProfileContent(
                profile = (profileState as ProfileUiState.Loaded).profile,
                avatarDisplaySource = avatarDisplaySource,
                isAvatarUploading = avatarUploadState is ProfileViewModel.AvatarUploadState.Uploading,
                showDevPanel = showDevPanel,
                notificationStatusText = notificationStatusText,
                notificationActionLabel = notificationActionLabel,
                onLogout = onLogout,
                onSettings = onSettings,
                onNotificationAction = onNotificationAction,
                onEditAvatar = onEditAvatar,
                onEditUsername = onEditUsername,
                onCopyFriendCode = onCopyFriendCode,
                onShareFriendCode = onShareFriendCode,
                onUseStreakFreeze = onUseStreakFreeze
            )
        }
    }
}

@Composable
private fun EmptyProfileState(onRetry: () -> Unit) {
    val spacing = WanderlyTheme.spacing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        WanderlyEmptyState(
            title = stringResource(R.string.profile_empty_title),
            message = stringResource(R.string.profile_empty_subtitle),
            actionLabel = stringResource(R.string.common_retry),
            onAction = onRetry,
            icon = Icons.Filled.Person
        )
    }
}

@Composable
private fun ProfileContent(
    profile: Profile,
    avatarDisplaySource: String?,
    isAvatarUploading: Boolean,
    showDevPanel: Boolean,
    notificationStatusText: String?,
    notificationActionLabel: String?,
    onLogout: () -> Unit,
    onSettings: () -> Unit,
    onNotificationAction: () -> Unit,
    onEditAvatar: () -> Unit,
    onEditUsername: () -> Unit,
    onCopyFriendCode: (String) -> Unit,
    onShareFriendCode: (String) -> Unit,
    onUseStreakFreeze: () -> Unit
) {
    val spacing = WanderlyTheme.spacing
    val honey = profile.honey ?: 0
    val streak = profile.streak_count ?: 0
    val streakFreezes = profile.streak_freezes ?: 0
    val streakAtRisk = when (
        DailyStreakStatusEvaluator.evaluate(
            streakCount = streak,
            lastMissionDate = profile.last_mission_date,
            today = LocalDate.now(ZoneOffset.UTC)
        )
    ) {
        DailyStreakStatus.AT_RISK, DailyStreakStatus.FREEZE_ELIGIBLE -> true
        else -> false
    }
    val citiesVisited = profile.cities_visited?.size ?: 0
    val rankInt = HiveRank.fromHoney(honey)
    val displayName = profile.username?.takeIf { it.isNotBlank() } ?: stringResource(R.string.profile_default_name)
    val avatarSource = avatarDisplaySource ?: profile.avatar_url

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = spacing.maxContentWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.lg)
        ) {
            ProfileHero(
                displayName = displayName,
                profile = profile,
                avatarSource = avatarSource,
                isAvatarUploading = isAvatarUploading,
                rankInt = rankInt,
                showDevPanel = showDevPanel,
                onEditAvatar = onEditAvatar,
                onEditUsername = onEditUsername,
                onSettings = onSettings,
                onCopyFriendCode = onCopyFriendCode,
                onShareFriendCode = onShareFriendCode
            )

            Spacer(modifier = Modifier.height(spacing.lg))
            ProfileStatsRow(
                honey = honey,
                streak = streak,
                citiesVisited = citiesVisited
            )

            if (streak > 0 || streakFreezes > 0) {
                Spacer(modifier = Modifier.height(spacing.lg))
                ProfileStreakFreezePanel(
                    freezesLeft = streakFreezes,
                    atRisk = streakAtRisk,
                    onUseFreeze = onUseStreakFreeze
                )
            }

            if (notificationStatusText != null && notificationActionLabel != null) {
                Spacer(modifier = Modifier.height(spacing.lg))
                ProfileNotificationPanel(
                    statusText = notificationStatusText,
                    actionLabel = notificationActionLabel,
                    onAction = onNotificationAction
                )
            }

            Spacer(modifier = Modifier.height(spacing.lg))
            ProfileProgressPanel(honey = honey, rankInt = rankInt)

            Spacer(modifier = Modifier.height(spacing.xl))
            ProfileBadgesPanel(profile = profile)

            Spacer(modifier = Modifier.height(spacing.xl))
            ProfileActions(onLogout = onLogout)

            Spacer(modifier = Modifier.height(spacing.xl))
        }
    }
}

@Preview(name = "Light - Empty Profile")
@Preview(name = "Dark - Empty Profile", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewEmptyProfileState() {
    WanderlyTheme {
        EmptyProfileState(onRetry = {})
    }
}
