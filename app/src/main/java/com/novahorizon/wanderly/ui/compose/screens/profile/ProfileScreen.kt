package com.novahorizon.wanderly.ui.compose.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.asFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.ui.compose.components.BuzzyMascot
import com.novahorizon.wanderly.ui.compose.components.LoadingState
import com.novahorizon.wanderly.ui.compose.components.RankChip
import com.novahorizon.wanderly.ui.compose.components.WanderlyCard
import com.novahorizon.wanderly.ui.compose.util.rankDisplayName
import com.novahorizon.wanderly.ui.compose.util.rankProgress
import com.novahorizon.wanderly.ui.profile.ProfileViewModel
import com.novahorizon.wanderly.ui.profile.ProfileViewModel.ProfileUiState

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onLogout: () -> Unit,
    onSettings: () -> Unit,
    onEditAvatar: () -> Unit
) {
    val profileState by viewModel.profileState.asFlow().collectAsStateWithLifecycle(ProfileUiState.Loading)
    val profile by viewModel.profile.asFlow().collectAsStateWithLifecycle(null)

    when (profileState) {
        is ProfileUiState.Loading -> {
            LoadingState(message = "Loading profile...")
        }
        is ProfileUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Could not load profile",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        is ProfileUiState.Loaded -> {
            profile?.let { p ->
                ProfileContent(
                    profile = p,
                    onLogout = onLogout,
                    onSettings = onSettings,
                    onEditAvatar = onEditAvatar
                )
            }
        }
    }
}

@Composable
private fun ProfileContent(
    profile: Profile,
    onLogout: () -> Unit,
    onSettings: () -> Unit,
    onEditAvatar: () -> Unit
) {
    val honey = profile.honey ?: 0
    val rankInt = HiveRank.fromHoney(honey)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        WanderlyCard(elevation = 4.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    BuzzyMascot(size = 64.dp)
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = profile.username ?: "Explorer",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                profile.friend_code?.let { code ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Friend Code: $code",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                RankChip(rankName = rankDisplayName(rankInt))

                profile.explorer_class?.let { cls ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = cls.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                icon = "🍯",
                label = "HONEY",
                value = "%,d".format(honey),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = "🔥",
                label = "STREAK",
                value = "${profile.streak_count ?: 0}",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = "🌍",
                label = "CITIES",
                value = "${profile.cities_visited?.size ?: 0}",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        WanderlyCard {
            Text(
                text = "Hive Progress",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { rankProgress(honey, rankInt) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "%,d / %,d honey to next rank".format(honey, HiveRank.minHoneyForRank(rankInt + 1)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Badges",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        val badgeIcons = listOf(
            "🏆" to "First Flight",
            "🔥" to "7-Day Streak",
            "📷" to "Photographer",
            "🗺️" to "Cartographer",
            "💎" to "Gem Finder",
            "👑" to "Queen Bee"
        )
        val earnedBadges = profile.badges ?: emptyList()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            badgeIcons.take(3).forEach { (icon, name) ->
                BadgeItem(
                    icon = icon,
                    name = name,
                    locked = !earnedBadges.contains(name.lowercase().replace(" ", "_")),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            badgeIcons.drop(3).forEach { (icon, name) ->
                BadgeItem(
                    icon = icon,
                    name = name,
                    locked = !earnedBadges.contains(name.lowercase().replace(" ", "_")),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(text = "Log Out", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatCard(
    icon: String,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    WanderlyCard(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = icon, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun BadgeItem(
    icon: String,
    name: String,
    locked: Boolean,
    modifier: Modifier = Modifier
) {
    val alpha = if (locked) 0.4f else 1f
    val stateLabel = if (locked) "Locked" else "Earned"
    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = "$name badge, $stateLabel"
            }
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = icon,
            fontSize = 24.sp,
            modifier = Modifier.then(
                if (locked) Modifier else Modifier
            )
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            textAlign = TextAlign.Center
        )
    }
}
