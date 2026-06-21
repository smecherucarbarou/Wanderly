package com.novahorizon.wanderly.ui.compose.components

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView as AndroidTextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.novahorizon.wanderly.ui.common.AvatarLoader

@Composable
fun AvatarImage(
    avatarSource: String?,
    displayName: String,
    modifier: Modifier = Modifier,
    initialTextColor: Color,
    initialTextSize: TextUnit = 18.sp
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                val imageView = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    visibility = View.GONE
                }
                val initialView = AndroidTextView(context).apply {
                    gravity = Gravity.CENTER
                    textSize = initialTextSize.value
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(initialTextColor.toArgb())
                }
                addView(imageView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(initialView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                tag = AvatarViews(imageView, initialView)
            }
        },
        update = { avatarFrame ->
            val avatarViews = avatarFrame.tag as AvatarViews
            avatarViews.initialView.textSize = initialTextSize.value
            avatarViews.initialView.setTextColor(initialTextColor.toArgb())
            AvatarLoader.loadAvatar(
                imageView = avatarViews.imageView,
                initialView = avatarViews.initialView,
                avatarSource = avatarSource,
                displayName = displayName
            )
        }
    )
}

private data class AvatarViews(
    val imageView: ImageView,
    val initialView: AndroidTextView
)
