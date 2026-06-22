package com.novahorizon.wanderly.ui.compose.screens.profile

import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.ui.compose.components.AvatarImage
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.ui.profile.ProfileFragment

@Composable
internal fun ProfileAvatar(
    avatarSource: String?,
    displayName: String,
    streakCount: Int,
    isUploading: Boolean,
    onEditAvatar: () -> Unit,
    equippedFrameSku: String? = null
) {
    val editAvatarDescription = stringResource(R.string.cd_edit_avatar)
    val haloStyle = ProfileFragment.resolveProfileHaloStyle(streakCount)
    val frameColor = when (equippedFrameSku) {
        "frame_gold" -> Color(0xFFFFD24A)
        "frame_hex" -> Color(0xFFF2A03D)
        else -> MaterialTheme.colorScheme.primary
    }
    val frameWidth = if (equippedFrameSku == null) 3.dp else 4.dp
    Box(
        modifier = Modifier.size(if (haloStyle == null) 104.dp else 124.dp),
        contentAlignment = Alignment.Center
    ) {
        haloStyle?.let { style ->
            DrawableImage(
                drawableRes = style.glowRes,
                modifier = Modifier.fillMaxSize()
            )
            DrawableImage(
                drawableRes = style.ringRes,
                modifier = Modifier.fillMaxSize()
            )
            DrawableImage(
                drawableRes = style.accentRes,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(frameWidth, frameColor, CircleShape)
                .clickable(enabled = !isUploading, onClick = onEditAvatar)
                .semantics {
                    role = Role.Button
                    contentDescription = editAvatarDescription
                },
            contentAlignment = Alignment.Center
        ) {
            AvatarImage(
                avatarSource = avatarSource,
                displayName = displayName,
                modifier = Modifier.fillMaxSize(),
                initialTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                initialTextSize = 34.sp
            )

            if (isUploading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.74f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-8).dp, y = (-8).dp)
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .padding(5.dp),
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun DrawableImage(
    @DrawableRes drawableRes: Int,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
                setImageResource(drawableRes)
            }
        },
        update = { view ->
            view.setImageResource(drawableRes)
        }
    )
}

@Composable
internal fun FriendCodeRow(
    code: String,
    onCopyFriendCode: (String) -> Unit,
    onShareFriendCode: (String) -> Unit
) {
    val spacing = WanderlyTheme.spacing

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.md, end = spacing.sm, bottom = spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.profile_friend_code_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = code,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { onCopyFriendCode(code) }) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_friend_code)
                )
            }
            IconButton(onClick = { onShareFriendCode(code) }) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.cd_share_friend_code)
                )
            }
        }
    }
}
