package com.novahorizon.wanderly.ui.compose.screens.profile

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView as AndroidTextView
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.ui.common.AvatarLoader
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme

@Composable
internal fun ProfileAvatar(
    avatarSource: String?,
    displayName: String,
    isUploading: Boolean,
    onEditAvatar: () -> Unit
) {
    val editAvatarDescription = stringResource(R.string.cd_edit_avatar)
    val initialTextColor = MaterialTheme.colorScheme.onPrimaryContainer.let { color ->
        android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
    }
    Box(
        modifier = Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .clickable(enabled = !isUploading, onClick = onEditAvatar)
            .semantics {
                role = Role.Button
                contentDescription = editAvatarDescription
            },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                FrameLayout(context).apply {
                    val imageView = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        visibility = View.GONE
                    }
                    val initialView = AndroidTextView(context).apply {
                        gravity = Gravity.CENTER
                        textSize = 34f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(initialTextColor)
                    }
                    addView(imageView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    addView(initialView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    tag = AvatarViews(imageView, initialView)
                }
            },
            update = { avatarFrame ->
                val avatarViews = avatarFrame.tag as AvatarViews
                AvatarLoader.loadAvatar(
                    imageView = avatarViews.imageView,
                    initialView = avatarViews.initialView,
                    avatarSource = avatarSource,
                    displayName = displayName
                )
            }
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

private data class AvatarViews(
    val imageView: ImageView,
    val initialView: AndroidTextView
)
