package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.Constants
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryAvatarStorageTargetTest {

    @Test
    fun `builds avatar storage target with stable upload path and versioned public url`() {
        val target = ProfileRepository.buildAvatarStorageTarget(
            baseUrl = "https://example.supabase.co",
            bucket = Constants.STORAGE_BUCKET_AVATARS,
            profileId = "user-123",
            versionToken = "1713698472000"
        )

        assertEquals("profiles/user-123/avatar.jpg", target.filePath)
        assertEquals(
            "https://example.supabase.co/storage/v1/object/avatars/profiles/user-123/avatar.jpg",
            target.uploadUrl
        )
        assertTrue(
            target.publicUrl.startsWith(
                "https://example.supabase.co/storage/v1/object/public/avatars/profiles/user-123/avatar.jpg"
            )
        )
        assertTrue(target.publicUrl.contains("?v=1713698472000"))
        assertTrue(target.useUpsert)
    }

    @Test
    fun `builds detailed avatar upload failure message`() {
        assertEquals(
            "Avatar upload failed with code 403: row violates row-level security policy",
            ProfileRepository.buildAvatarUploadFailureMessage(
                code = 403,
                responseBody = "row violates row-level security policy"
            )
        )
    }

    @Test
    fun `builds fallback avatar upload failure message when response body is blank`() {
        assertEquals(
            "Avatar upload failed with code 500",
            ProfileRepository.buildAvatarUploadFailureMessage(
                code = 500,
                responseBody = "   "
            )
        )
    }

    @Test
    fun `database timeout upload response maps to retryable user friendly avatar error`() {
        val error = AvatarRepository.toAvatarUploadError(
            code = 544,
            responseBody = """{"code":"DatabaseTimeout"}"""
        )

        assertEquals("Upload failed. Please try again.", error.message)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `unexpected upload response maps to generic non retryable avatar error`() {
        val error = AvatarRepository.toAvatarUploadError(
            code = 500,
            responseBody = """{"code":"SomethingElse"}"""
        )

        assertEquals("Could not upload avatar. Please try another image.", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `extracts local file path from file uri`() {
        assertEquals(
            "/data/user/0/com.novahorizon.wanderly/cache/temp_avatar.jpg",
            ProfileRepository.extractLocalFilePath(
                scheme = "file",
                path = "/data/user/0/com.novahorizon.wanderly/cache/temp_avatar.jpg"
            )
        )
    }

    @Test
    fun `returns null for non file uri local path extraction`() {
        assertNull(
            ProfileRepository.extractLocalFilePath(
                scheme = "content",
                path = "/external/images/media/42"
            )
        )
    }

    @Test
    fun `avatar crop file is usable only when it exists and has bytes`() {
        assertTrue(ProfileRepository.isAvatarFileUsable(exists = true, length = 128))
        assertFalse(ProfileRepository.isAvatarFileUsable(exists = true, length = 0))
        assertFalse(ProfileRepository.isAvatarFileUsable(exists = false, length = 128))
        assertFalse(
            ProfileRepository.isAvatarFileUsable(
                exists = true,
                length = AvatarRepository.MAX_AVATAR_UPLOAD_BYTES + 1L
            )
        )
    }

    @Test
    fun `avatar uri upload reads through content resolver and resolves mime type`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/data/AvatarRepository.kt").readText()

        assertTrue(source.contains("private fun readAvatarBytes(uri: Uri): ByteArray"))
        assertTrue(source.contains("context.contentResolver.openInputStream(uri)"))
        assertTrue(source.contains("private fun resolveAvatarMimeType(uri: Uri): String"))
        assertTrue(source.contains("private const val AVATAR_MIME_TYPE = \"image/jpeg\""))
        assertFalse(source.contains("File(uri.path!!"))
    }

    @Test
    fun `avatar upload owns staged storage and profile update diagnostics`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/data/AvatarRepository.kt").readText()

        assertTrue(source.contains("stage = \"read_bytes\""))
        assertTrue(source.contains("stage = \"validate_user\""))
        assertTrue(source.contains("stage = \"storage_upload\""))
        assertTrue(source.contains("stage = \"public_url\""))
        assertTrue(source.contains("stage = \"profile_update\""))
        assertTrue(source.contains("require(currentUserId == profileId)"))
        assertTrue(source.contains("Profile::avatar_url setTo publicUrl"))
        assertTrue(source.contains("Avatar upload start currentUser=$"))
        assertTrue(source.contains("Avatar upload failed stage=$"))
    }

    private fun projectFile(relativePath: String): File {
        return File(projectRoot(), relativePath)
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }
}
