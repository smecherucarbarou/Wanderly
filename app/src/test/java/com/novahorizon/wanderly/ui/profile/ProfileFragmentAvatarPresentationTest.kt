package com.novahorizon.wanderly.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFragmentAvatarPresentationTest {

    @Test
    fun `uses profile avatar when there is no pending preview`() {
        val decision = ProfileFragment.resolveAvatarPresentation(
            profileAvatarSource = "profiles/user-1/avatar-1.jpg",
            pendingAvatarPreviewSource = null,
            pendingAvatarRemotePath = null
        )

        assertEquals("profiles/user-1/avatar-1.jpg", decision.displaySource)
        assertFalse(decision.shouldClearPendingPreview)
    }

    @Test
    fun `keeps local preview while uploaded avatar path is not confirmed yet`() {
        val decision = ProfileFragment.resolveAvatarPresentation(
            profileAvatarSource = "profiles/user-1/avatar-old.jpg",
            pendingAvatarPreviewSource = "file:///cache/new-avatar.jpg",
            pendingAvatarRemotePath = "profiles/user-1/avatar-new.jpg"
        )

        assertEquals("file:///cache/new-avatar.jpg", decision.displaySource)
        assertFalse(decision.shouldClearPendingPreview)
    }

    @Test
    fun `clears pending preview once profile contains the same uploaded avatar path`() {
        val decision = ProfileFragment.resolveAvatarPresentation(
            profileAvatarSource = "https://aimparysgwabameqjgsv.supabase.co/storage/v1/object/public/avatars/profiles/user-1/avatar-new.jpg",
            pendingAvatarPreviewSource = "file:///cache/new-avatar.jpg",
            pendingAvatarRemotePath = "profiles/user-1/avatar-new.jpg"
        )

        assertEquals(
            "https://aimparysgwabameqjgsv.supabase.co/storage/v1/object/public/avatars/profiles/user-1/avatar-new.jpg",
            decision.displaySource
        )
        assertTrue(decision.shouldClearPendingPreview)
    }
}
