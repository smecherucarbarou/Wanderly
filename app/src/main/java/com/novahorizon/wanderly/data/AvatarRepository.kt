package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.observability.AppLogger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
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
            logError("Upload failed bucket=$bucket path=${target.filePath} error=${e.message}", e)
            AvatarUploadResult.Error("Could not upload avatar. Please try another image.")
        }
    }

    suspend fun uploadAvatar(uri: Uri, profileId: String): String = withContext(Dispatchers.IO) {
        val auth = SupabaseClient.client.auth
        val accessToken = auth.currentAccessTokenOrNull() ?: run {
            logError("No access token available for avatar upload")
            throw IllegalStateException("No access token available for avatar upload")
        }

        logDebug("Uploading avatar")

        try {
            val avatarBytes = buildAvatarBytes(uri) ?: run {
                logError("Could not read image bytes from: $uri")
                throw IllegalStateException("Could not read image bytes from: $uri")
            }

            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
            val bucket = Constants.STORAGE_BUCKET_AVATARS
            val target = buildAvatarStorageTarget(
                baseUrl = baseUrl,
                bucket = bucket,
                profileId = profileId,
                versionToken = clock.nowMillis().toString(),
                mimeType = "image/jpeg"
            )

            logDebug("Target upload URL: ${target.uploadUrl}")

            val request = Request.Builder()
                .url(target.uploadUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .apply { if (target.useUpsert) addHeader("x-upsert", "true") }
                .post(avatarBytes.toRequestBody("image/jpeg".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    val message = buildAvatarUploadFailureMessage(response.code, responseBody)
                    logError(message)
                    throw IllegalStateException(message)
                }
                logDebug("Avatar upload successful")
            }

            logDebug("Generated avatar storage path")
            target.filePath
        } catch (e: Exception) {
            CrashReporter.recordNonFatal(
                CrashEvent.PROFILE_AVATAR_UPLOAD_FAILED,
                e,
                CrashKey.COMPONENT to "profile",
                CrashKey.OPERATION to "avatar_upload"
            )
            logError("Exception during avatar upload", e)
            throw e
        }
    }

    private fun buildAvatarBytes(uri: Uri): ByteArray? {
        val localFilePath = extractLocalFilePath(uri.scheme, uri.path)
        if (localFilePath != null) {
            val avatarFile = File(localFilePath)
            if (!isAvatarFileUsable(avatarFile.exists(), avatarFile.length())) {
                return null
            }
            return avatarFile.readBytes()
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        openAvatarInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, bounds)
        } ?: return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, reqWidth = 512, reqHeight = 512)
        }
        val bitmap = openAvatarInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        } ?: return null

        return ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            outputStream.toByteArray()
        }
    }

    private fun openAvatarInputStream(uri: Uri) = extractLocalFilePath(uri.scheme, uri.path)?.let { FileInputStream(it) }
        ?: context.contentResolver.openInputStream(uri)

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
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
    data class Error(val message: String) : AvatarUploadResult()
    object UnsupportedFormat : AvatarUploadResult()
    object FileTooLarge : AvatarUploadResult()
}
