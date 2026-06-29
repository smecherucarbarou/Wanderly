package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.observability.AppLogger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.util.Clock
import com.novahorizon.wanderly.util.RealClock
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class AvatarRepository(
    private val context: Context,
    private val clock: Clock = RealClock
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

    suspend fun uploadAvatar(uri: Uri, profileId: String): AvatarUploadResult = withContext(Dispatchers.IO) {
        var stage = "read_bytes"
        try {
            // Bound the raw input before readBytes() pulls it fully into memory (OOM guard).
            val rawSize = queryAvatarSize(uri)
            if (rawSize != null && rawSize > MAX_RAW_AVATAR_BYTES) {
                return@withContext AvatarUploadResult.FileTooLarge
            }
            val avatarBytes = readAvatarBytes(uri)
            val mimeType = resolveAvatarMimeType(uri)
            validateAvatarPayload(avatarBytes, mimeType)?.let { return@withContext it }

            stage = "validate_user"
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            val currentUserId = session?.user?.id
            require(!currentUserId.isNullOrBlank()) {
                "No authenticated Supabase user for avatar upload"
            }
            require(currentUserId == profileId) {
                "Avatar upload user mismatch"
            }
            val accessToken = SupabaseClient.client.auth.currentAccessTokenOrNull()
            require(!accessToken.isNullOrBlank()) {
                "No authenticated Supabase user for avatar upload"
            }

            val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
            val bucket = AVATAR_BUCKET
            val target = buildAvatarStorageTarget(
                baseUrl = baseUrl,
                bucket = bucket,
                profileId = profileId,
                versionToken = clock.nowMillis().toString(),
                mimeType = mimeType
            )

            logDebug(
                "Avatar upload start bucket=$bucket mimeType=$mimeType bytes=${avatarBytes.size}"
            )

            stage = "storage_upload"
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
                    val failure = IllegalStateException(message)
                    logStageFailure(stage, failure)
                    val error = toAvatarUploadError(response.code, responseBody)
                    if (!error.isRetryable) {
                        CrashReporter.recordNonFatal(
                            CrashEvent.PROFILE_AVATAR_UPLOAD_FAILED,
                            failure,
                            CrashKey.COMPONENT to "profile",
                            CrashKey.OPERATION to "avatar_upload_http"
                        )
                    }
                    return@withContext AvatarUploadResult.Error(
                        message = stageFailureMessage(stage, error.isRetryable),
                        isRetryable = error.isRetryable
                    )
                }
            }

            stage = "public_url"
            val publicUrl = target.publicUrl.takeIf { it.isNotBlank() }
                ?: error("Could not build public avatar URL")

            stage = "profile_update"
            SupabaseClient.client.postgrest[Constants.TABLE_PROFILES].update({
                Profile::avatar_url setTo publicUrl
            }) {
                filter { eq("id", profileId) }
            }

            logDebug("Avatar upload success bucket=$bucket")
            AvatarUploadResult.Success(target.publicUrl)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logStageFailure(stage, e)
            CrashReporter.recordNonFatal(
                CrashEvent.PROFILE_AVATAR_UPLOAD_FAILED,
                e,
                CrashKey.COMPONENT to "profile",
                CrashKey.OPERATION to "avatar_upload_$stage"
            )
            AvatarUploadResult.Error(stageFailureMessage(stage))
        }
    }

    /** Best-effort size of the picked content via OpenableColumns.SIZE; null if the provider omits it. */
    private fun queryAvatarSize(uri: Uri): Long? {
        return context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
            }
    }

    private fun readAvatarBytes(uri: Uri): ByteArray {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes()
        } ?: throw IllegalStateException("Could not read avatar image bytes")
        return recompressAsJpeg(rawBytes)
    }

    private fun recompressAsJpeg(rawBytes: ByteArray): ByteArray {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw IllegalStateException("Could not decode avatar image")
        }
        val maxDim = MAX_AVATAR_DIMENSION
        val sampleSize = maxOf(options.outWidth / maxDim, options.outHeight / maxDim, 1)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
            ?: throw IllegalStateException("Could not decode avatar image")
        val bitmap = if (sampled.width > maxDim || sampled.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(sampled.width, sampled.height)
            val scaled = Bitmap.createScaledBitmap(
                sampled,
                (sampled.width * scale).toInt().coerceAtLeast(1),
                (sampled.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== sampled) sampled.recycle()
            scaled
        } else {
            sampled
        }
        val jpegBytes = try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        return stripExifMetadata(jpegBytes)
    }

    private fun stripExifMetadata(jpegBytes: ByteArray): ByteArray {
        val tempFile = java.io.File.createTempFile("avatar_exif_strip", ".jpg", context.cacheDir)
        try {
            tempFile.writeBytes(jpegBytes)
            val exif = android.media.ExifInterface(tempFile.absolutePath)
            EXIF_TAGS_TO_STRIP.forEach { tag -> exif.setAttribute(tag, null) }
            exif.saveAttributes()
            return tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    private fun resolveAvatarMimeType(uri: Uri): String = AVATAR_MIME_TYPE

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

    private fun logStageFailure(stage: String, throwable: Throwable) {
        logDebug(
            "Avatar upload failed stage=$stage type=${throwable::class.simpleName} " +
                "message=${throwable.message}"
        )
    }

    private fun stageFailureMessage(stage: String, retryable: Boolean = false): String {
        return if (retryable) {
            "Avatar upload failed at $stage. Please try again."
        } else {
            "Avatar upload failed at $stage. Please try another image."
        }
    }

    companion object {
        private const val AVATAR_BUCKET = "avatars"
        private const val AVATAR_MIME_TYPE = "image/jpeg"
        private const val UPLOAD_AVATAR_URL_MARKER = "/storage/v1/object/avatars/"
        private const val PUBLIC_AVATAR_URL_MARKER = "/storage/v1/object/public/avatars/"
        private const val AUTHENTICATED_AVATAR_URL_MARKER = "/storage/v1/object/authenticated/avatars/"
        internal const val MAX_AVATAR_UPLOAD_BYTES = 5L * 1024L * 1024L
        // Upper bound on the RAW picked image before it is read into memory and recompressed. Guards against
        // OOM from a huge source file; the recompressed result is separately capped by MAX_AVATAR_UPLOAD_BYTES.
        internal const val MAX_RAW_AVATAR_BYTES = 25L * 1024L * 1024L
        private const val MAX_AVATAR_DIMENSION = 512
        private const val JPEG_QUALITY = 80
        private val ALLOWED_MIME = setOf("image/jpeg", "image/png", "image/webp")
        private val EXIF_TAGS_TO_STRIP = listOf(
            android.media.ExifInterface.TAG_GPS_LATITUDE,
            android.media.ExifInterface.TAG_GPS_LONGITUDE,
            android.media.ExifInterface.TAG_GPS_LATITUDE_REF,
            android.media.ExifInterface.TAG_GPS_LONGITUDE_REF,
            android.media.ExifInterface.TAG_GPS_ALTITUDE,
            android.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
            android.media.ExifInterface.TAG_GPS_TIMESTAMP,
            android.media.ExifInterface.TAG_GPS_DATESTAMP,
            android.media.ExifInterface.TAG_DATETIME,
            android.media.ExifInterface.TAG_DATETIME_ORIGINAL,
            android.media.ExifInterface.TAG_DATETIME_DIGITIZED,
            android.media.ExifInterface.TAG_MAKE,
            android.media.ExifInterface.TAG_MODEL,
            android.media.ExifInterface.TAG_SOFTWARE
        )

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
    // Keep avatar path aligned with Storage RLS: profiles/<auth.uid()>/avatar.jpg.
    val objectPath = "profiles/$profileId/avatar.jpg"
    val cacheBustedPublicUrl =
        "$normalizedBaseUrl/storage/v1/object/public/$bucket/$objectPath?v=$versionToken"

    return AvatarStorageTarget(
        filePath = objectPath,
        uploadUrl = "$normalizedBaseUrl/storage/v1/object/$bucket/$objectPath",
        publicUrl = cacheBustedPublicUrl,
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
    data class Success(val avatarUrl: String) : AvatarUploadResult()
    data class Error(val message: String, val isRetryable: Boolean = false) : AvatarUploadResult()
    object UnsupportedFormat : AvatarUploadResult()
    object FileTooLarge : AvatarUploadResult()
}
