package com.novahorizon.wanderly.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarPathBuilderTest {

    @Test
    fun `avatar path uses profiles uid folder`() {
        val uid = "00000000-0000-0000-0000-000000000000"

        assertEquals("profiles/$uid/avatar.jpg", AvatarPathBuilder.build(uid, "image/jpeg"))
        assertEquals("profiles/$uid/avatar.webp", AvatarPathBuilder.build(uid, "image/webp"))
        assertEquals("profiles/$uid/avatar.png", AvatarPathBuilder.build(uid, "image/png"))
    }

    @Test
    fun `avatar path defaults unknown image mime to jpg`() {
        val uid = "user-123"

        assertEquals("profiles/$uid/avatar.jpg", AvatarPathBuilder.build(uid, "image/heic"))
    }

    @Test
    fun `oversized avatar payload returns file too large`() {
        val oversized = ByteArray((AvatarRepository.MAX_AVATAR_UPLOAD_BYTES + 1).toInt())

        assertEquals(
            AvatarUploadResult.FileTooLarge,
            AvatarRepository.validateAvatarPayload(oversized, "image/jpeg")
        )
    }

    @Test
    fun `unsupported avatar mime returns unsupported format`() {
        assertEquals(
            AvatarUploadResult.UnsupportedFormat,
            AvatarRepository.validateAvatarPayload(ByteArray(16), "image/heic")
        )
    }

    @Test
    fun `valid avatar payload passes local validation`() {
        assertNull(AvatarRepository.validateAvatarPayload(ByteArray(16), "image/webp"))
    }
}
