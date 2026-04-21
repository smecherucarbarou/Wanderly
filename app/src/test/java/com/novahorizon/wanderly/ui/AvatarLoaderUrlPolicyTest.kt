package com.novahorizon.wanderly.ui

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.ui.common.AvatarLoader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarLoaderUrlPolicyTest {

    @Test
    fun `allows only https remote avatar urls`() {
        assertTrue(AvatarLoader.isRemoteAvatarUrlAllowed("https://example.com/avatar.jpg"))
        assertTrue(AvatarLoader.isRemoteAvatarUrlAllowed(" HTTPS://example.com/avatar.jpg "))
        assertFalse(AvatarLoader.isRemoteAvatarUrlAllowed("http://example.com/avatar.jpg"))
        assertFalse(AvatarLoader.isRemoteAvatarUrlAllowed("ftp://example.com/avatar.jpg"))
        assertFalse(AvatarLoader.isRemoteAvatarUrlAllowed("not-a-url"))
    }

    @Test
    fun `recognizes local avatar uri sources`() {
        assertTrue(AvatarLoader.isLocalAvatarUri("file:///data/user/0/app/cache/avatar.jpg"))
        assertTrue(AvatarLoader.isLocalAvatarUri(" content://media/external/images/media/42 "))
        assertFalse(AvatarLoader.isLocalAvatarUri("https://example.com/avatar.jpg"))
        assertFalse(AvatarLoader.isLocalAvatarUri("not-a-url"))
    }

    @Test
    fun `extracts storage path from legacy public avatar url`() {
        val path = AvatarLoader.extractSupabaseStoragePath(
            "https://aimparysgwabameqjgsv.supabase.co/storage/v1/object/public/avatars/profiles/user-1/avatar-123.jpg"
        )

        assertEquals("profiles/user-1/avatar-123.jpg", path)
    }

    @Test
    fun `extracts storage path from legacy upload avatar url`() {
        val path = AvatarLoader.extractSupabaseStoragePath(
            "https://aimparysgwabameqjgsv.supabase.co/storage/v1/object/avatars/profiles/user-1/avatar-123.jpg"
        )

        assertEquals("profiles/user-1/avatar-123.jpg", path)
    }

    @Test
    fun `keeps raw storage path as is`() {
        assertEquals(
            "profiles/user-1/avatar-123.jpg",
            AvatarLoader.extractSupabaseStoragePath("profiles/user-1/avatar-123.jpg")
        )
    }

    @Test
    fun `builds authenticated avatar url from storage path`() {
        assertEquals(
            "${BuildConfig.SUPABASE_URL}/storage/v1/object/authenticated/avatars/profiles/user-1/avatar-123.jpg",
            AvatarLoader.buildSupabaseAuthenticatedAvatarUrl(
                baseUrl = BuildConfig.SUPABASE_URL,
                storagePath = "profiles/user-1/avatar-123.jpg"
            )
        )
    }

    @Test
    fun `builds public avatar url from storage path`() {
        assertEquals(
            "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/avatars/profiles/user-1/avatar-123.jpg",
            AvatarLoader.buildSupabasePublicAvatarUrl(
                baseUrl = BuildConfig.SUPABASE_URL,
                storagePath = "profiles/user-1/avatar-123.jpg"
            )
        )
    }

    @Test
    fun `returns null when source is not a supabase avatar path`() {
        assertNull(AvatarLoader.extractSupabaseStoragePath("https://example.com/avatar.jpg"))
    }

    @Test
    fun `normalizes raw storage path to public avatar url`() {
        assertEquals(
            "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/avatars/profiles/user-1/avatar-123.jpg",
            AvatarLoader.normalizeAvatarSourceForDisplay("profiles/user-1/avatar-123.jpg")
        )
    }

    @Test
    fun `normalizes legacy public url to canonical public avatar url`() {
        assertEquals(
            "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/avatars/profiles/user-1/avatar-123.jpg",
            AvatarLoader.normalizeAvatarSourceForDisplay(
                "https://aimparysgwabameqjgsv.supabase.co/storage/v1/object/public/avatars/profiles/user-1/avatar-123.jpg?download=1"
            )
        )
    }

    @Test
    fun `normalizes legacy upload url to canonical public avatar url`() {
        assertEquals(
            "${BuildConfig.SUPABASE_URL}/storage/v1/object/public/avatars/profiles/user-1/avatar-123.jpg",
            AvatarLoader.normalizeAvatarSourceForDisplay(
                "https://aimparysgwabameqjgsv.supabase.co/storage/v1/object/avatars/profiles/user-1/avatar-123.jpg"
            )
        )
    }

    @Test
    fun `keeps local and external sources unchanged when normalizing display source`() {
        assertEquals(
            "file:///data/user/0/app/cache/avatar.jpg",
            AvatarLoader.normalizeAvatarSourceForDisplay("file:///data/user/0/app/cache/avatar.jpg")
        )
        assertEquals(
            "https://example.com/avatar.jpg",
            AvatarLoader.normalizeAvatarSourceForDisplay("https://example.com/avatar.jpg")
        )
    }
}
