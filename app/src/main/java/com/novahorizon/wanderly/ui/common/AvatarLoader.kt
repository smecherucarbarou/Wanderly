package com.novahorizon.wanderly.ui.common

import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.novahorizon.wanderly.R

object AvatarLoader {
    private const val TAG = "AvatarLoader"

    fun loadAvatar(
        imageView: ImageView,
        initialView: TextView,
        avatarSource: String?,
        displayName: String
    ) {
        if (avatarSource.isNullOrBlank()) {
            showInitial(initialView, imageView, displayName)
            return
        }

        val trimmedSource = avatarSource.trim()
        val requestBuilder = when {
            trimmedSource.startsWith("http://", ignoreCase = true) ||
                trimmedSource.startsWith("https://", ignoreCase = true) -> {
                if (!isRemoteAvatarUrlAllowed(trimmedSource)) {
                    Log.w(TAG, "Blocked non-HTTPS avatar URL.")
                    showPlaceholder(initialView, imageView)
                    return
                }
                Glide.with(imageView).load(trimmedSource)
            }

            else -> {
                val imageBytes = decodeLegacyAvatar(trimmedSource)
                if (imageBytes == null) {
                    showInitial(initialView, imageView, displayName)
                    return
                }
                Glide.with(imageView).load(imageBytes)
            }
        }

        showInitial(initialView, imageView, displayName)
        requestBuilder
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .circleCrop()
            .error(R.drawable.ic_buzzy)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    showInitial(initialView, imageView, displayName)
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    initialView.visibility = View.GONE
                    imageView.visibility = View.VISIBLE
                    return false
                }
            })
            .into(imageView)
    }

    internal fun isRemoteAvatarUrlAllowed(source: String): Boolean {
        return source.trim().startsWith("https://", ignoreCase = true)
    }

    private fun showInitial(initialView: TextView, imageView: ImageView, displayName: String) {
        imageView.visibility = View.GONE
        initialView.visibility = View.VISIBLE
        initialView.text = displayName.firstOrNull()?.uppercase() ?: "E"
    }

    private fun showPlaceholder(initialView: TextView, imageView: ImageView) {
        initialView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        imageView.setImageResource(R.drawable.ic_buzzy)
    }

    private fun decodeLegacyAvatar(avatarSource: String): ByteArray? {
        return try {
            if (avatarSource.startsWith("data:image", ignoreCase = true)) {
                Base64.decode(avatarSource.substringAfter("base64,"), Base64.DEFAULT)
            } else {
                Base64.decode(avatarSource, Base64.DEFAULT)
            }
        } catch (_: Exception) {
            null
        }
    }
}
