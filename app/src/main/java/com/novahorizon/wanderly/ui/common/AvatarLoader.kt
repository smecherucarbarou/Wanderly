package com.novahorizon.wanderly.ui.common

import com.novahorizon.wanderly.observability.AppLogger

import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.auth.auth

object AvatarLoader {
    private const val TAG = "AvatarLoader"
    private const val UPLOAD_AVATAR_URL_MARKER = "/storage/v1/object/avatars/"
    private const val PUBLIC_AVATAR_URL_MARKER = "/storage/v1/object/public/avatars/"
    private const val AUTHENTICATED_AVATAR_URL_MARKER = "/storage/v1/object/authenticated/avatars/"

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
        val requestBuilder: RequestBuilder<android.graphics.drawable.Drawable> = when {
            trimmedSource.startsWith("http://", ignoreCase = true) ||
                    trimmedSource.startsWith("https://", ignoreCase = true) -> {
                val remoteRequest = buildSupabasePrimaryRequest(imageView, trimmedSource)
                when {
                    remoteRequest != null -> remoteRequest
                    !isRemoteAvatarUrlAllowed(trimmedSource) -> {
                        AppLogger.w(TAG, "Blocked non-HTTPS avatar URL.")
                        showPlaceholder(initialView, imageView)
                        return
                    }

                    else -> Glide.with(imageView).load(trimmedSource).error(R.drawable.ic_buzzy)
                }
            }

            extractSupabaseStoragePath(trimmedSource) != null -> {
                buildSupabasePrimaryRequest(imageView, trimmedSource) ?: run {
                    logDebug("Could not build a Supabase avatar model for storage source.")
                    showInitial(initialView, imageView, displayName)
                    return
                }
            }

            isLocalAvatarUri(trimmedSource) -> {
                Glide.with(imageView).load(trimmedSource)
            }

            else -> Glide.with(imageView).load(trimmedSource).error(R.drawable.ic_buzzy)
        }

        requestBuilder
            .override(160, 160)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .circleCrop()
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (BuildConfig.DEBUG) {
                        AppLogger.e(
                            TAG,
                            "Avatar load failed for model=${LogRedactor.redact(model?.toString())}: " +
                                LogRedactor.redact(e?.message)
                        )
                    }
                    else AppLogger.e(TAG, "Avatar load failed")
                    showInitial(initialView, imageView, displayName)
                    return true
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

    internal fun isLocalAvatarUri(source: String): Boolean {
        val trimmed = source.trim()
        return trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true)
    }

    internal fun extractSupabaseStoragePath(source: String): String? {
        val trimmed = source.trim()
        return when {
            trimmed.startsWith("profiles/") -> trimmed.substringBefore('?')
            trimmed.contains(UPLOAD_AVATAR_URL_MARKER) -> trimmed.substringAfter(UPLOAD_AVATAR_URL_MARKER).substringBefore('?')
            trimmed.contains(PUBLIC_AVATAR_URL_MARKER) -> trimmed.substringAfter(PUBLIC_AVATAR_URL_MARKER).substringBefore('?')
            trimmed.contains(AUTHENTICATED_AVATAR_URL_MARKER) -> trimmed.substringAfter(AUTHENTICATED_AVATAR_URL_MARKER).substringBefore('?')
            else -> null
        }
    }

    internal fun buildSupabaseAuthenticatedAvatarUrl(baseUrl: String, storagePath: String): String {
        return "${baseUrl.trimEnd('/')}$AUTHENTICATED_AVATAR_URL_MARKER${storagePath.trimStart('/')}"
    }

    internal fun buildSupabasePublicAvatarUrl(baseUrl: String, storagePath: String): String {
        return "${baseUrl.trimEnd('/')}$PUBLIC_AVATAR_URL_MARKER${storagePath.trimStart('/')}"
    }

    internal fun normalizeAvatarSourceForDisplay(source: String): String {
        val trimmed = source.trim()
        if (isLocalAvatarUri(trimmed)) return trimmed
        val storagePath = extractSupabaseStoragePath(trimmed) ?: return trimmed
        return buildSupabasePublicAvatarUrl(BuildConfig.SUPABASE_URL, storagePath)
    }

    private fun buildSupabasePrimaryRequest(
        imageView: ImageView,
        source: String
    ): RequestBuilder<android.graphics.drawable.Drawable>? {
        val storagePath = extractSupabaseStoragePath(source) ?: return null
        val publicUrl = buildSupabasePublicAvatarUrl(BuildConfig.SUPABASE_URL, storagePath)
        val authenticatedFallback = buildSupabaseAuthenticatedModel(storagePath)
        logDebug(
            "Loading Supabase avatar primary=$publicUrl fallback=${
                if (authenticatedFallback != null) "authenticated" else "none"
            }"
        )
        return Glide.with(imageView)
            .load(publicUrl)
            .let { requestBuilder ->
                if (authenticatedFallback != null) {
                    requestBuilder.error(
                        Glide.with(imageView)
                            .load(authenticatedFallback)
                            .override(160, 160)
                            .circleCrop()
                            .error(R.drawable.ic_buzzy)
                    )
                } else {
                    requestBuilder.error(R.drawable.ic_buzzy)
                }
            }
    }

    private fun buildSupabaseAuthenticatedModel(source: String): GlideUrl? {
        val storagePath = extractSupabaseStoragePath(source) ?: return null
        val accessToken = runCatching { SupabaseClient.client.auth.currentAccessTokenOrNull() }.getOrNull()
            ?: run {
                logDebug("No access token available for authenticated avatar load. Falling back to public URL.")
                return null
            }
        val authenticatedUrl = buildSupabaseAuthenticatedAvatarUrl(BuildConfig.SUPABASE_URL, storagePath)
        val headers = LazyHeaders.Builder()
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()
        return GlideUrl(authenticatedUrl, headers)
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, LogRedactor.redact(message))
        }
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
