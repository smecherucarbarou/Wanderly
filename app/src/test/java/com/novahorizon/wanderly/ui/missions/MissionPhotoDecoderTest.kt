package com.novahorizon.wanderly.ui.missions

import android.app.Application
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MissionPhotoDecoderTest {

    @Test
    fun decodeForVerificationDownsamplesLargeImages() {
        val encodedImage = createPng(width = 3_200, height = 2_400)

        val bitmap = MissionPhotoDecoder.decodeForVerification {
            ByteArrayInputStream(encodedImage)
        }

        assertNotNull(bitmap)
        bitmap!!
        assertTrue(bitmap.width <= MissionPhotoDecoder.MAX_DIMENSION_PX)
        assertTrue(bitmap.height <= MissionPhotoDecoder.MAX_DIMENSION_PX)
    }

    @Test
    fun decodeForVerificationReturnsNullForInvalidImage() {
        val bitmap = MissionPhotoDecoder.decodeForVerification {
            ByteArrayInputStream("not an image".toByteArray())
        }

        assertNull(bitmap)
    }

    @Test
    fun decodeForVerificationReturnsNullForMissingStream() {
        val bitmap = MissionPhotoDecoder.decodeForVerification { null }

        assertNull(bitmap)
    }

    private fun createPng(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }
}
