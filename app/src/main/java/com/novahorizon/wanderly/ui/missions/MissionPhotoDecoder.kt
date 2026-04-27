package com.novahorizon.wanderly.ui.missions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.InputStream
import kotlin.math.roundToInt

object MissionPhotoDecoder {
    const val MAX_DIMENSION_PX = 1_280

    fun decodeForVerification(openInputStream: () -> InputStream?): Bitmap? {
        if (!hasSupportedImageHeader(openInputStream)) {
            return null
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        openInputStream()?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimension = MAX_DIMENSION_PX
            )
        }

        val decoded = openInputStream()?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null

        return scaleDownIfNeeded(decoded)
    }

    internal fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        var sampledWidth = width
        var sampledHeight = height

        while (sampledWidth > maxDimension || sampledHeight > maxDimension) {
            inSampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }

        return inSampleSize
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= MAX_DIMENSION_PX) return bitmap

        val scale = MAX_DIMENSION_PX.toDouble() / longestSide.toDouble()
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = bitmap.scale(targetWidth, targetHeight)
        if (scaled != bitmap) {
            bitmap.recycle()
        }
        return scaled
    }

    private fun hasSupportedImageHeader(openInputStream: () -> InputStream?): Boolean {
        val header = ByteArray(12)
        val bytesRead = openInputStream()?.use { input ->
            input.read(header)
        } ?: return false

        if (bytesRead < 4) return false

        val isJpeg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
        val isPng = header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte()
        val isWebp = bytesRead >= 12 &&
            header[0] == 0x52.toByte() &&
            header[1] == 0x49.toByte() &&
            header[2] == 0x46.toByte() &&
            header[3] == 0x46.toByte() &&
            header[8] == 0x57.toByte() &&
            header[9] == 0x45.toByte() &&
            header[10] == 0x42.toByte() &&
            header[11] == 0x50.toByte()

        return isJpeg || isPng || isWebp
    }
}
