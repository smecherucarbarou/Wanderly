package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.observability.AppLogger

import android.content.Context
import android.net.Uri
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.util.Clock
import com.novahorizon.wanderly.util.SystemClock
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AvatarRepository(
    private val context: Context,
    private val clock: Clock = SystemClock
) {
    internal data class AvatarStorageTarget(
        val filePath: String,
        val uploadUrl: String,
        val publicUrl: String,
        val useUpsert: Boolean
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun uploadAvatar(
        imageBytes: ByteArray,
        mimeType: String
    ): AvatarUploadResult = withContext(Dispatchers.IO) {
        validateAvatarPayload(imageBytes, mimeType)?.let { return@withContext it }
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext AvatarUploadResult.Error("User must be signed in to upload avatar")
        val uid = session.user?.id
            ?: return@withContext AvatarUploadResult.Error("User must be signed in to upload avatar")
        val accessToken = SupabaseClient.client.auth.currentAccessTokenOrNull()
            ?: return@withContext AvatarUploadResult.Error("User must be signed in to upload avatar")
        val bucket = Constants.STORAGE_BUCKET_AVATARS
        val target = buildAvatarStorageTarget(
            baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/'),
            bucket = bucket,
            profileId = uid,
            versionToken = clock.nowMillis().toString(),
            mimeType = mimeType
        )

        try {
            val request = Request.Builder()
                .url(target.uploadUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("x-upsert", "true")
                .post(imageBytes.toRequestBody(mimeType.toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    val message = buildAvatarUploadFailureMessage(response.code, responseBody)
                    logError("Upload failed bucket=$bucket path=${target.filePath} error=$message")
                    return@withContext AvatarUploadResult.Error("Could not upload avatar. Please try another image.")
                }
            }

            logDebug("Upload success bucket=$bucket path=${target.filePath}")
            AvatarUploadResult.Success(target.filePath)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Upload failed bucket=$bucket path=${target.filePath} error=${e.message}", e)
            AvatarUploadResult.Error("Could not upload avatar. Please try another image.")
        }
    }

    suspend fun uploadAvatar(uri: Uri, profileId: String): AvatarUploadResult = withContext(Dispatchers.IO) {
        val auth = SupabaseClient.client.auth
        val accessToken = auth.currentAccessTokenOrNull() ?: run {
            logError("No access token available for avatar upload")
            return@withContext AvatarUploadResult.Error("User must be signed in to upload avatar")
        }

        logDebug("Uploading avatar")

        try {
            val avatarBytes = readAvatarBytes(uri)
            val mimeType = resolveAvatarMimeType(uri)
            validateAvatarPayload(avatarBytes, mimeType)?.let { return@withContext it }

            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
            val bucket = Constants.STORAGE_BUCKET_AVATARS
            val target = buildAvatarStorageTarget(
                baseUrl = baseUrl,
                bucket = bucket,
                profileId = profileId,
                versionToken = clock.nowMillis().toString(),
                mimeType = mimeType
            )

            logDebug("Target upload URL: ${target.uploadUrl}")

            val request = Request.Builder()
                .url(target.uploadUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .apply { if (target.useUpsert) addHeader("x-upsert", "true") }
                .post(avatarBytes.toRequestBody(mimeType.toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    val message = buildAvatarUploadFailureMessage(response.code, responseBody)
                    logError(message)
                    val error = toAvatarUploadError(response.code, responseBody)
                    if (!error.isRetryable) {
                        CrashReporter.recordNonFatal(
                            CrashEvent.PROFILE_AVATAR_UPLOAD_FAILED,
                            IllegalStateException(message),
                            CrashKey.COMPONENT to "profile",
                            CrashKey.OPERATION to "avatar_upload_http"
                        )
                    }
                    return@withContext error
                }
                logDebug("Avatar upload successful")
            }

            logDebug("Generated avatar storage path")
            AvatarUploadResult.Success(target.filePath)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            CrashReporter.recordNonFatal(
                CrashEvent.PROFILE_AVATAR_UPLOAD_FAILED,
                e,
                CrashKey.COMPONENT to "profile",
                CrashKey.OPERATION to "avatar_upload"
            )
            logError("Exception during avatar upload", e)
            AvatarUploadResult.Error("Could not upload avatar. Please try another image.")
        }
    }

    private fun readAvatarBytes(uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: throw IllegalStateException("Could not open avatar input stream")
    }

    private fun resolveAvatarMimeType(uri: Uri): String {
        return context.contentResolver.getType(uri) ?: "image/jpeg"
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d("AvatarRepository", LogRedactor.redact(message))
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val safeMessage = LogRedactor.redact(message)
            if (throwable != null) {
                AppLogger.e(
                    "AvatarRepository",
                    "$safeMessage [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
                )
            } else {
                AppLogger.e("AvatarRepository", safeMessage)
            }
        }
    }

    companion object {
        private const val UPLOAD_AVATAR_URL_MARKER = "/storage/v1/object/avatars/"
        private const val PUBLIC_AVATAR_URL_MARKER = "/storage/v1/object/public/avatars/"
        private const val AUTHENTICATED_AVATAR_URL_MARKER = "/storage/v1/object/authenticated/avatars/"
        internal const val MAX_AVATAR_UPLOAD_BYTES = 2L * 1024L * 1024L
        private val ALLOWED_MIME = setOf("image/jpeg", "image/png", "image/webp")

        internal fun validateAvatarPayload(imageBytes: ByteArray, mimeType: String): AvatarUploadResult? {
            if (mimeType !in ALLOWED_MIME) return AvatarUploadResult.UnsupportedFormat
            if (imageBytes.size > MAX_AVATAR_UPLOAD_BYTES) return AvatarUploadResult.FileTooLarge
            return null
        }

        internal fun normalizeAvatarUrl(avatarUrl: String?): String? {
            val trimmed = avatarUrl?.trim().orEmpty()
            if (trimmed.isEmpty()) return null

            return when {
                trimmed.startsWith("profiles/") -> trimmed.substringBefore('?')
                trimmed.contains(UPLOAD_AVATAR_URL_MARKER) -> trimmed.substringAfter(UPLOAD_AVATAR_URL_MARKER).substringBefore('?')
                trimmed.contains(PUBLIC_AVATAR_URL_MARKER) -> trimmed.substringAfter(PUBLIC_AVATAR_URL_MARKER).substringBefore('?')
                trimmed.contains(AUTHENTICATED_AVATAR_URL_MARKER) -> trimmed.substringAfter(AUTHENTICATED_AVATAR_URL_MARKER).substringBefore('?')
                else -> trimmed
            }
        }

        internal fun buildAvatarStorageTarget(
            baseUrl: String,
            bucket: String,
            profileId: String,
            versionToken: String,
            mimeType: String = "image/jpeg"
        ): AvatarStorageTarget {
            val normalizedBaseUrl = baseUrl.trimEnd('/')
            val filePath = AvatarPathBuilder.build(profileId, mimeType)
            return AvatarStorageTarget(
                filePath = filePath,
                uploadUrl = "$normalizedBaseUrl/storage/v1/object/$bucket/$filePath",
                publicUrl = "$normalizedBaseUrl/storage/v1/object/public/$bucket/$filePath?v=$versionToken",
                useUpsert = true
            )
        }

        internal fun buildAvatarUploadFailureMessage(code: Int, responseBody: String): String {
            val trimmedResponse = responseBody.trim()
            return if (trimmedResponse.isEmpty()) {
                "Avatar upload failed with code $code"
            } else {
                "Avatar upload failed with code $code: $trimmedResponse"
            }
        }

        internal fun toAvatarUploadError(code: Int, responseBody: String): AvatarUploadResult.Error {
            return if (code == 544 || responseBody.contains("DatabaseTimeout", ignoreCase = true)) {
                AvatarUploadResult.Error(
                    message = "Upload failed. Please try again.",
                    isRetryable = true
                )
            } else {
                AvatarUploadResult.Error(
                    message = "Could not upload avatar. Please try another image.",
                    isRetryable = false
                )
            }
        }

        internal fun extractLocalFilePath(scheme: String?, path: String?): String? {
            return path?.takeIf { scheme.equals("file", ignoreCase = true) && it.isNotBlank() }
        }

        internal fun isAvatarFileUsable(exists: Boolean, length: Long): Boolean {
            return exists && length in 1L..MAX_AVATAR_UPLOAD_BYTES
        }
    }
}

sealed class AvatarUploadResult {
    data class Success(val path: String) : AvatarUploadResult()
    data class Error(val message: String, val isRetryable: Boolean = false) : AvatarUploadResult()
    object UnsupportedFormat : AvatarUploadResult()
    object FileTooLarge : AvatarUploadResult()
}
