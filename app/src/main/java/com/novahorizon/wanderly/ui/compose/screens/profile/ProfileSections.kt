package com.novahorizon.wanderly.ui.compose.screens.profile

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.StreakMilestoneStatus
import com.novahorizon.wanderly.ui.compose.components.RankChip
import com.novahorizon.wanderly.ui.compose.components.WanderlyCard
import com.novahorizon.wanderly.ui.compose.components.WanderlyStatCard
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.ui.compose.util.rankDisplayName
import com.novahorizon.wanderly.ui.compose.util.rankProgress
import com.novahorizon.wanderly.widgets.StreakTierHelper

@Composable
internal fun ProfileHero(
    displayName: String,
    profile: Profile,
    avatarSource: String?,
    isAvatarUploading: Boolean,
    rankInt: Int,
    showDevPanel: Boolean,
    onEditAvatar: () -> Unit,
    onEditUsername: () -> Unit,
    onSettings: () -> Unit,
    onCopyFriendCode: (String) -> Unit,
    onShareFriendCode: (String) -> Unit
) {
    val spacing = WanderlyTheme.spacing

    WanderlyCard(elevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            ProfileAvatar(
                avatarSource = avatarSource,
                displayName = displayName,
                streakCount = profile.streak_count ?: 0,
                isUploading = isAvatarUploading,
                onEditAvatar = onEditAvatar
            )

            Spacer(modifier = Modifier.width(spacing.lg))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    RankChip(rankName = rankDisplayName(rankInt))
                    profile.explorer_class?.takeIf { it.isNotBlank() }?.let { explorerClass ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = explorerClass,
                                modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.lg))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Button(
                onClick = onEditAvatar,
                enabled = !isAvatarUploading,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = spacing.minTouchTarget),
                shape = MaterialTheme.shapes.medium
            ) {
                if (isAvatarUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(
                        text = stringResource(R.string.profile_avatar_uploading),
                        maxLines = 2
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(
                        text = stringResource(R.string.profile_avatar_change),
                        maxLines = 2
                    )
                }
            }
            OutlinedButton(
                onClick = onEditUsername,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = spacing.minTouchTarget),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(spacing.sm))
                Text(
                    text = stringResource(R.string.profile_edit_name),
                    maxLines = 2
                )
            }
        }

        profile.friend_code?.takeIf { it.isNotBlank() }?.let { code ->
            Spacer(modifier = Modifier.height(spacing.lg))
            FriendCodeRow(
                code = code,
                onCopyFriendCode = onCopyFriendCode,
                onShareFriendCode = onShareFriendCode
            )
        }

        if (showDevPanel) {
            Spacer(modifier = Modifier.height(spacing.lg))
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = spacing.minTouchTarget),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Filled.AdminPanelSettings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(spacing.sm))
                Text(
                    text = stringResource(R.string.profile_dev_panel),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
internal fun ProfileStatsRow(
    honey: Int,
    streak: Int,
    citiesVisited: Int,
    modifier: Modifier = Modifier
) {
    val spacing = WanderlyTheme.spacing

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        WanderlyStatCard(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_honeycomb),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            label = stringResource(R.string.profile_honey_label),
            value = "%,d".format(honey),
            modifier = Modifier.weight(1f)
        )
        WanderlyStatCard(
            icon = {
                Icon(
                    painter = painterResource(StreakTierHelper.resolve(streak).animFile),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color.Unspecified
                )
            },
            label = stringResource(R.string.profile_streak_label),
            value = streak.toString(),
            modifier = Modifier.weight(1f)
        )
        WanderlyStatCard(
            icon = {
                Icon(
                    imageVector = Icons.Filled.Public,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            label = stringResource(R.string.profile_cities_label),
            value = citiesVisited.toString(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun ProfileProgressPanel(honey: Int, rankInt: Int) {
    val spacing = WanderlyTheme.spacing

    WanderlyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.profile_rank_progress),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = rankDisplayName(rankInt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "%,d".format(honey),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(spacing.md))
        LinearProgressIndicator(
            progress = { rankProgress(honey, rankInt) },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(MaterialTheme.shapes.small),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = stringResource(
                R.string.profile_rank_progress_format,
                "%,d".format(honey),
                "%,d".format(HiveRank.minHoneyForRank(rankInt + 1))
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ProfileStreakFreezePanel(
    freezesLeft: Int,
    atRisk: Boolean,
    onUseFreeze: () -> Unit
) {
    val spacing = WanderlyTheme.spacing

    WanderlyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Icon(
                imageVector = Icons.Filled.AcUnit,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.profile_streak_freeze_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = if (atRisk) {
                        stringResource(R.string.profile_streak_freeze_at_risk)
                    } else {
                        pluralStringResource(
                            R.plurals.profile_streak_freeze_available,
                            freezesLeft,
                            freezesLeft
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.md))
        OutlinedButton(
            onClick = onUseFreeze,
            enabled = freezesLeft > 0,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.minTouchTarget),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = stringResource(R.string.profile_streak_freeze_button),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun ProfileNotificationPanel(
    statusText: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    val spacing = WanderlyTheme.spacing

    WanderlyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.profile_notifications_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = stringResource(R.string.profile_notifications_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(spacing.md))
        OutlinedButton(
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = spacing.minTouchTarget),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = actionLabel,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun ProfileMilestonesPanel(
    milestones: List<StreakMilestoneStatus>,
    onClaim: (Int) -> Unit
) {
    if (milestones.isEmpty()) return
    val spacing = WanderlyTheme.spacing

    WanderlyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Icon(
                imageVector = Icons.Filled.EmojiEvents,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.profile_streak_milestones_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        milestones.forEach { milestone ->
            Spacer(modifier = Modifier.height(spacing.md))
            ProfileMilestoneRow(milestone = milestone, onClaim = onClaim)
        }
    }
}

@Composable
private fun ProfileMilestoneRow(
    milestone: StreakMilestoneStatus,
    onClaim: (Int) -> Unit
) {
    val spacing = WanderlyTheme.spacing

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = milestone.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = stringResource(R.string.profile_streak_milestone_requirement, milestone.threshold) +
                    "  ·  " +
                    stringResource(R.string.profile_streak_milestone_reward, "%,d".format(milestone.rewardHoney)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            milestone.claimable -> {
                Button(
                    onClick = { onClaim(milestone.threshold) },
                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.xs),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = stringResource(R.string.profile_streak_milestone_claim),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            milestone.claimed -> {
                MilestoneStatusChip(text = stringResource(R.string.profile_streak_milestone_claimed_label))
            }
            else -> {
                MilestoneStatusChip(text = stringResource(R.string.profile_streak_milestone_locked_label))
            }
        }
    }
}

@Composable
private fun MilestoneStatusChip(text: String) {
    val spacing = WanderlyTheme.spacing
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
internal fun ProfileActions(onLogout: () -> Unit) {
    val spacing = WanderlyTheme.spacing

    OutlinedButton(
        onClick = onLogout,
        modifier = Modifier
            .fillMaxWidth()
            .height(spacing.buttonHeight),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Logout,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            text = stringResource(R.string.profile_logout_button),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Preview(name = "Light - Profile Summary")
@Preview(name = "Dark - Profile Summary", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewProfileSummary() {
    WanderlyTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ProfileHero(
                displayName = "Preview Explorer",
                profile = Profile(
                    id = "preview-1",
                    username = "Preview Explorer",
                    honey = 1450,
                    friend_code = "BZZ123",
                    explorer_class = "Trail Scout"
                ),
                avatarSource = null,
                isAvatarUploading = false,
                rankInt = 2,
                showDevPanel = false,
                onEditAvatar = {},
                onEditUsername = {},
                onSettings = {},
                onCopyFriendCode = {},
                onShareFriendCode = {}
            )
            ProfileStatsRow(honey = 1450, streak = 7, citiesVisited = 3)
            ProfileProgressPanel(honey = 1450, rankInt = 2)
            ProfileBadgesPanel(
                profile = Profile(
                    id = "preview-1",
                    username = "Preview Explorer",
                    honey = 1450,
                    badges = listOf("first_flight", "photographer")
                )
            )
        }
    }
}
