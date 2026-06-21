package com.novahorizon.wanderly.ui.compose.screens.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.ui.compose.components.WanderlySectionHeader
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme

@Composable
internal fun ProfileBadgesPanel(profile: Profile) {
    val spacing = WanderlyTheme.spacing
    val badgeDefinitions = listOf(
        BadgeDefinition(
            icon = Icons.Filled.EmojiEvents,
            displayName = stringResource(R.string.badge_first_flight),
            id = "first_flight",
            aliases = setOf("Early Bee"),
            requirement = stringResource(R.string.badge_first_flight_requirement)
        ),
        BadgeDefinition(
            icon = Icons.Filled.LocalFireDepartment,
            displayName = stringResource(R.string.badge_7_day_streak),
            id = "7-day_streak",
            aliases = setOf("Streak Master"),
            requirement = stringResource(R.string.badge_7_day_streak_requirement)
        ),
        BadgeDefinition(
            icon = Icons.Filled.CameraAlt,
            displayName = stringResource(R.string.badge_photographer),
            id = "photographer",
            aliases = setOf("Photographer"),
            requirement = stringResource(R.string.badge_photographer_requirement)
        ),
        BadgeDefinition(
            icon = Icons.Filled.Map,
            displayName = stringResource(R.string.badge_cartographer),
            id = "cartographer",
            aliases = setOf("Cartographer"),
            requirement = stringResource(R.string.badge_cartographer_requirement)
        ),
        BadgeDefinition(
            icon = Icons.Filled.Diamond,
            displayName = stringResource(R.string.badge_gem_finder),
            id = "gem_finder",
            aliases = setOf("Gem Finder"),
            requirement = stringResource(R.string.badge_gem_finder_requirement)
        ),
        BadgeDefinition(
            icon = Icons.Filled.Star,
            displayName = stringResource(R.string.badge_queen_bee),
            id = "queen_bee",
            aliases = setOf("Queen Explorer", "Honey Hoarder"),
            requirement = stringResource(R.string.badge_queen_bee_requirement)
        )
    )
    val earnedBadges = profile.badges.orEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        WanderlySectionHeader(title = stringResource(R.string.profile_badges_title))
        Spacer(modifier = Modifier.height(spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            badgeDefinitions.take(3).forEach { badge ->
                BadgeItem(
                    icon = badge.icon,
                    name = badge.displayName,
                    requirement = badge.requirement,
                    locked = !badge.isEarned(earnedBadges),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            badgeDefinitions.drop(3).forEach { badge ->
                BadgeItem(
                    icon = badge.icon,
                    name = badge.displayName,
                    requirement = badge.requirement,
                    locked = !badge.isEarned(earnedBadges),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BadgeItem(
    icon: ImageVector,
    name: String,
    requirement: String,
    locked: Boolean,
    modifier: Modifier = Modifier
) {
    val spacing = WanderlyTheme.spacing
    val stateLabel = if (locked) {
        stringResource(R.string.profile_badge_locked)
    } else {
        stringResource(R.string.profile_badge_earned)
    }
    val badgeDescription = stringResource(R.string.profile_badge_description, name, stateLabel)
    val iconColor = if (locked) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.62f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val textColor = if (locked) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val backgroundColor = if (locked) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (locked) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    }

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 96.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = badgeDescription
            },
        color = backgroundColor,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = iconColor
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = if (locked) requirement else stateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class BadgeDefinition(
    val icon: ImageVector,
    val displayName: String,
    val id: String,
    val aliases: Set<String>,
    val requirement: String
) {
    fun isEarned(earnedBadges: List<String>): Boolean {
        val accepted = (aliases + id).map { it.normalizedBadgeId() }.toSet()
        return earnedBadges.any { it.normalizedBadgeId() in accepted }
    }
}

private fun String.normalizedBadgeId(): String =
    trim()
        .lowercase()
        .replace('_', '-')
        .replace(Regex("\\s+"), "-")
