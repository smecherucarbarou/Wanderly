package com.novahorizon.wanderly.ui.compose.screens.social

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.asFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.ActiveHiveChallenge
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.ui.compose.components.AvatarImage
import com.novahorizon.wanderly.ui.compose.components.ErrorState
import com.novahorizon.wanderly.ui.compose.components.HoneyButton
import com.novahorizon.wanderly.ui.compose.components.LoadingState
import com.novahorizon.wanderly.ui.compose.components.WanderlyCard
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.ui.social.SocialViewModel
import java.util.Locale

@Composable
fun SocialScreen(
    viewModel: SocialViewModel,
    onAddFriend: (String) -> Unit,
    onAcceptFriendRequest: (String) -> Unit = {},
    onRejectFriendRequest: (String) -> Unit = {},
    onBrowseMissions: () -> Unit = {},
    onCopyCode: (String) -> Unit = {},
    onShareCode: (String) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentProfile by viewModel.currentProfile.asFlow().collectAsStateWithLifecycle(null)
    val hiveChallenge by viewModel.hiveChallenge.asFlow().collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    val isLoading = state is SocialViewModel.SocialUiState.Loading
    val loadedState = state as? SocialViewModel.SocialUiState.Loaded
    val leaderboard = loadedState?.leaderboard.orEmpty()
    val friends = loadedState?.friends.orEmpty()
    val incomingRequests = loadedState?.incomingRequests.orEmpty()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var friendCode by rememberSaveable { mutableStateOf("") }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.social_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        hiveChallenge?.let { challenge ->
            HiveChallengePanel(challenge = challenge)
            Spacer(modifier = Modifier.height(16.dp))
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabPositions ->
                SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.social_tab_leaderboard)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.social_tab_friends)) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        when {
            isLoading -> {
                LoadingState(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            state is SocialViewModel.SocialUiState.Error -> {
                ErrorState(
                    message = (state as SocialViewModel.SocialUiState.Error).message.asString(context),
                    onRetry = { viewModel.loadSocialHome() },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            else -> {
                val profiles = if (selectedTab == 0) leaderboard else friends

                if (selectedTab == 1 && profiles.isEmpty() && incomingRequests.isEmpty()) {
                    FriendsEmptyState(
                        friendCode = currentProfile?.friend_code,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        onCopyCode = onCopyCode,
                        onShareCode = onShareCode
                    )
                } else if (selectedTab == 0 && profiles.isEmpty()) {
                    LeaderboardEmptyState(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        onBrowseMissions = onBrowseMissions
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f)
                    ) {
                        if (selectedTab == 1 && incomingRequests.isNotEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.social_incoming_requests_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                )
                            }
                            itemsIndexed(incomingRequests, key = { _, profile -> "request-${profile.id}" }) { _, profile ->
                                FriendRequestRow(
                                    profile = profile,
                                    onAccept = { onAcceptFriendRequest(profile.id) },
                                    onReject = { onRejectFriendRequest(profile.id) }
                                )
                            }
                            if (friends.isNotEmpty()) {
                                item {
                                    Text(
                                        text = stringResource(R.string.social_friends_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                    )
                                }
                            }
                        }
                        itemsIndexed(profiles, key = { _, profile -> profile.id }) { index, profile ->
                            FriendRow(
                                rank = index + 1,
                                profile = profile
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Add friend section
        WanderlyCard {
            Text(
                text = stringResource(R.string.social_add_friend_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.social_add_friend_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = friendCode,
                    onValueChange = { value ->
                        friendCode = value
                            .uppercase(Locale.US)
                            .filter { it.isLetterOrDigit() }
                            .take(6)
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.social_add_friend_title)) },
                    placeholder = { Text(stringResource(R.string.social_friend_code_placeholder)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.width(10.dp))
                HoneyButton(
                    text = if (isLoading) stringResource(R.string.social_adding_friend) else stringResource(R.string.social_add_button),
                    onClick = {
                        if (friendCode.length == 6) {
                            onAddFriend(friendCode)
                            friendCode = ""
                        }
                    },
                    enabled = !isLoading && friendCode.length == 6,
                    modifier = Modifier.width(96.dp)
                )
            }
        }
    }
    }
}

/*
        if (isLoading) {
            LoadingState()
        } else {
            val profiles = if (selectedTab == 0) leaderboard else friends

            if (profiles.isEmpty()) {
                if (selectedTab == 0) {
                    LeaderboardEmptyState(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        onBrowseMissions = onBrowseMissions
                    )
                } else {
                    FriendsEmptyState(
                        friendCode = currentProfile?.friend_code,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        onCopyCode = onCopyCode,
                        onShareCode = onShareCode
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(profiles, key = { _, profile -> profile.id }) { index, profile ->
                        FriendRow(
                            rank = index + 1,
                            profile = profile
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Add friend section
        WanderlyCard {
            Text(
                text = stringResource(R.string.social_add_friend_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.social_add_friend_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = friendCode,
                    onValueChange = { value ->
                        friendCode = value
                            .uppercase(Locale.US)
                            .filter { it.isLetterOrDigit() }
                            .take(6)
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.social_add_friend_title)) },
                    placeholder = { Text(stringResource(R.string.social_friend_code_placeholder)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.width(10.dp))
                HoneyButton(
                    text = if (isLoading) stringResource(R.string.social_adding_friend) else stringResource(R.string.social_add_button),
                    onClick = {
                        if (friendCode.length == 6) {
                            onAddFriend(friendCode)
                            friendCode = ""
                        }
                    },
                    enabled = !isLoading && friendCode.length == 6,
                    modifier = Modifier.width(96.dp)
                )
            }
        }
    }
    }
}
*/

@Composable
private fun HiveChallengePanel(challenge: ActiveHiveChallenge) {
    WanderlyCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Groups,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.social_hive_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = challenge.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        challenge.description?.takeIf { it.isNotBlank() }?.let { description ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { challenge.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.social_hive_progress,
                    "%,d".format(challenge.totalContribution),
                    "%,d".format(challenge.goalTarget)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (challenge.goalReached) {
                    stringResource(R.string.social_hive_goal_reached)
                } else {
                    stringResource(R.string.social_hive_reward, "%,d".format(challenge.rewardHoney))
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LeaderboardEmptyState(
    modifier: Modifier = Modifier,
    onBrowseMissions: () -> Unit
) {
    Box(
        modifier = modifier.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.social_leaderboard_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            HoneyButton(
                text = stringResource(R.string.social_browse_missions),
                onClick = onBrowseMissions
            )
        }
    }
}

@Composable
private fun FriendsEmptyState(
    friendCode: String?,
    modifier: Modifier = Modifier,
    onCopyCode: (String) -> Unit,
    onShareCode: (String) -> Unit
) {
    Box(
        modifier = modifier.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (friendCode.isNullOrBlank()) {
                    stringResource(R.string.social_friends_empty_no_code)
                } else {
                    stringResource(R.string.social_friends_empty_with_code, friendCode)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (!friendCode.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onCopyCode(friendCode) }) {
                        Text(stringResource(R.string.social_copy_code))
                    }
                    TextButton(onClick = { onShareCode(friendCode) }) {
                        Text(stringResource(R.string.social_share))
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRow(
    rank: Int,
    profile: Profile
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#$rank",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(36.dp)
        )

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            AvatarImage(
                avatarSource = profile.avatar_url,
                displayName = profile.username ?: stringResource(R.string.social_default_username),
                modifier = Modifier.fillMaxSize(),
                initialTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                initialTextSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.username ?: stringResource(R.string.social_default_username),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = "%,d".format(profile.honey ?: 0),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun FriendRequestRow(
    profile: Profile,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            AvatarImage(
                avatarSource = profile.avatar_url,
                displayName = profile.username ?: stringResource(R.string.social_default_username),
                modifier = Modifier.fillMaxSize(),
                initialTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                initialTextSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = profile.username ?: stringResource(R.string.social_default_username),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        TextButton(onClick = onReject) {
            Text(stringResource(R.string.social_request_reject))
        }
        HoneyButton(
            text = stringResource(R.string.social_request_accept),
            onClick = onAccept,
            modifier = Modifier.width(96.dp)
        )
    }
}

// Previews

@Preview(name = "Light - Friend Row")
@Preview(name = "Dark - Friend Row", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewFriendRow() {
    WanderlyTheme {
        FriendRow(
            rank = 3,
            profile = Profile(
                id = "preview-1",
                username = "BuzzyExplorer",
                honey = 2450
            )
        )
    }
}

@Preview(name = "Light - Friend Request Row")
@Preview(name = "Dark - Friend Request Row", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewFriendRequestRow() {
    WanderlyTheme {
        FriendRequestRow(
            profile = Profile(
                id = "preview-2",
                username = "PollenPal"
            ),
            onAccept = {},
            onReject = {}
        )
    }
}

@Preview(name = "Light - Empty Leaderboard")
@Preview(name = "Dark - Empty Leaderboard", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewLeaderboardEmpty() {
    WanderlyTheme {
        LeaderboardEmptyState(onBrowseMissions = {})
    }
}

@Preview(name = "Light - Friends Empty")
@Preview(name = "Dark - Friends Empty", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewFriendsEmpty() {
    WanderlyTheme {
        FriendsEmptyState(
            friendCode = "ABC123",
            onCopyCode = {},
            onShareCode = {}
        )
    }
}
