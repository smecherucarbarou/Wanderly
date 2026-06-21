package com.novahorizon.wanderly.ui.compose.screens.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.ai.AiChatMessage
import com.novahorizon.wanderly.data.ai.AiChatRole
import com.novahorizon.wanderly.data.ai.AiQuotaResult
import com.novahorizon.wanderly.data.plus.PlusEntitlement
import com.novahorizon.wanderly.ui.compose.components.PlusUpsellCard
import com.novahorizon.wanderly.ui.compose.components.WanderlyEmptyState
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.ui.guide.WanderlyGuideUiState
import java.time.format.DateTimeFormatter

@Composable
fun WanderlyGuideScreen(
    state: WanderlyGuideUiState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onSendMessage: (String) -> Unit,
    onRetry: () -> Unit,
    showDebugPlusNote: Boolean
) {
    val spacing = WanderlyTheme.spacing
    val messages = state.messagesOrEmpty()
    val isSending = state is WanderlyGuideUiState.Loading
    val isQuotaExceeded = state is WanderlyGuideUiState.QuotaExceeded
    val entitlement = state.entitlementOrNull()
    val quota = state.quotaOrNull()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isSending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = spacing.maxContentWidth)
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = spacing.screenHorizontal)
        ) {
            GuideTopBar(
                entitlement = entitlement,
                onBack = onBack
            )

            when (state) {
                WanderlyGuideUiState.Unauthenticated -> {
                    UnauthenticatedGuideState(onLogin = onLogin)
                }

                WanderlyGuideUiState.Idle -> {
                    LoadingGuideState()
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(spacing.md)
                    ) {
                        if (messages.isEmpty()) {
                            item {
                                GuideEmptyPrompt(
                                    onPromptClick = { prompt ->
                                        input = prompt
                                        onSendMessage(prompt)
                                        input = ""
                                    }
                                )
                            }
                        } else {
                            items(messages, key = { it.id }) { message ->
                                ChatBubble(message = message)
                            }
                        }

                        if (state is WanderlyGuideUiState.Loading) {
                            item { AssistantThinkingBubble() }
                        }

                        if (state is WanderlyGuideUiState.QuotaExceeded) {
                            item {
                                QuotaExceededPanel(
                                    quota = state.quota,
                                    showDebugPlusNote = showDebugPlusNote
                                )
                            }
                        }

                        if (state is WanderlyGuideUiState.Error) {
                            item {
                                ErrorPanel(
                                    message = state.message,
                                    onRetry = onRetry
                                )
                            }
                        }
                    }

                    GuideInputBar(
                        value = input,
                        isSending = isSending,
                        isQuotaExceeded = isQuotaExceeded,
                        quota = quota,
                        onValueChange = { input = it },
                        onSend = {
                            val text = input.trim()
                            if (text.isNotBlank() && !isQuotaExceeded) {
                                onSendMessage(text)
                                input = ""
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideTopBar(
    entitlement: PlusEntitlement?,
    onBack: () -> Unit
) {
    val spacing = WanderlyTheme.spacing
    val isPlus = entitlement?.shouldShowActiveBadge() == true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.md, bottom = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = androidx.compose.ui.res.stringResource(R.string.cd_back)
            )
        }
        Icon(
            imageVector = Icons.Filled.Explore,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isPlus) {
            PlusBadge()
        }
    }
}

@Composable
private fun PlusBadge() {
    val spacing = WanderlyTheme.spacing
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.wanderly_plus_active),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun LoadingGuideState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun UnauthenticatedGuideState(onLogin: () -> Unit) {
    val spacing = WanderlyTheme.spacing
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        WanderlyEmptyState(
            title = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_login_title),
            message = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_login_body),
            actionLabel = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_login_action),
            onAction = onLogin,
            icon = Icons.Filled.Explore,
            modifier = Modifier.padding(bottom = spacing.xxl)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuideEmptyPrompt(onPromptClick: (String) -> Unit) {
    val spacing = WanderlyTheme.spacing
    val suggestions = listOf(
        androidx.compose.ui.res.stringResource(R.string.wanderly_guide_prompt_two_day),
        androidx.compose.ui.res.stringResource(R.string.wanderly_guide_prompt_cheap_food),
        androidx.compose.ui.res.stringResource(R.string.wanderly_guide_prompt_rainy_day),
        androidx.compose.ui.res.stringResource(R.string.wanderly_guide_prompt_hidden_gems)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.xl),
        horizontalAlignment = Alignment.Start
    ) {
        WanderlyEmptyState(
            title = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_empty_title),
            message = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_empty_subtitle),
            icon = Icons.Filled.Explore
        )
        Spacer(modifier = Modifier.height(spacing.lg))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            suggestions.forEach { suggestion ->
                AssistChip(
                    onClick = { onPromptClick(suggestion) },
                    label = {
                        Text(
                            text = suggestion,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: AiChatMessage) {
    val spacing = WanderlyTheme.spacing
    val isUser = message.role == AiChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 18.dp
            )
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(spacing.md),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun AssistantThinkingBubble() {
    val spacing = WanderlyTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.padding(spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_loading),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun QuotaExceededPanel(
    quota: AiQuotaResult,
    showDebugPlusNote: Boolean
) {
    val spacing = WanderlyTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_quota_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_quota_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (quota.resetDate.isNotBlank()) {
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        text = androidx.compose.ui.res.stringResource(
                            R.string.wanderly_guide_quota_reset_format,
                            quota.resetDate
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        PlusUpsellCard(showDebugNote = showDebugPlusNote)
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    onRetry: () -> Unit
) {
    val spacing = WanderlyTheme.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            Button(onClick = onRetry) {
                Text(text = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_error_retry))
            }
        }
    }
}

@Composable
private fun GuideInputBar(
    value: String,
    isSending: Boolean,
    isQuotaExceeded: Boolean,
    quota: AiQuotaResult?,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val spacing = WanderlyTheme.spacing
    val canSend = value.isNotBlank() && !isSending && !isQuotaExceeded

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.md)
    ) {
        quota?.let {
            Text(
                text = androidx.compose.ui.res.stringResource(
                    R.string.wanderly_guide_quota_remaining_format,
                    it.remaining,
                    it.limit
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = spacing.xs)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = !isSending && !isQuotaExceeded,
                label = {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_input_label))
                },
                placeholder = {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_input_placeholder))
                },
                minLines = 1,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() })
            )
            Button(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .widthIn(min = 88.dp, max = 112.dp)
                    .height(56.dp),
                contentPadding = PaddingValues(0.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = androidx.compose.ui.res.stringResource(R.string.wanderly_guide_send)
                )
            }
        }
    }
}

private fun WanderlyGuideUiState.messagesOrEmpty(): List<AiChatMessage> =
    when (this) {
        is WanderlyGuideUiState.Loading -> messages
        is WanderlyGuideUiState.Ready -> messages
        is WanderlyGuideUiState.QuotaExceeded -> messages
        is WanderlyGuideUiState.Error -> messages
        else -> emptyList()
    }

private fun WanderlyGuideUiState.entitlementOrNull(): PlusEntitlement? =
    when (this) {
        is WanderlyGuideUiState.Loading -> entitlement
        is WanderlyGuideUiState.Ready -> entitlement
        is WanderlyGuideUiState.QuotaExceeded -> entitlement
        is WanderlyGuideUiState.Error -> entitlement
        else -> null
    }

private fun WanderlyGuideUiState.quotaOrNull(): AiQuotaResult? =
    when (this) {
        is WanderlyGuideUiState.Loading -> quota
        is WanderlyGuideUiState.Ready -> quota
        is WanderlyGuideUiState.QuotaExceeded -> quota
        else -> null
    }
