package com.novahorizon.wanderly.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novahorizon.wanderly.R

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

    @Test
    fun `hides profile halo when streak is inactive`() {
        val haloStyle = ProfileFragment.resolveProfileHaloStyle(0)

        assertEquals(null, haloStyle)
    }

    @Test
    fun `uses layered base halo for starter streak tier`() {
        val haloStyle = ProfileFragment.resolveProfileHaloStyle(1)

        assertEquals(R.drawable.ic_profile_streak_glow, haloStyle?.glowRes)
        assertEquals(R.drawable.ic_profile_streak_ring, haloStyle?.ringRes)
        assertEquals(R.drawable.ic_profile_streak_sparks, haloStyle?.accentRes)

        assertEquals(R.drawable.ic_profile_streak_glow, ProfileFragment.resolveProfileHaloStyle(6)?.glowRes)
    }

    @Test
    fun `uses tiered halos that match streak tier boundaries`() {
        assertEquals(R.drawable.ic_profile_streak_glow_5, ProfileFragment.resolveProfileHaloStyle(7)?.glowRes)
        assertEquals(R.drawable.ic_profile_streak_ring_5, ProfileFragment.resolveProfileHaloStyle(7)?.ringRes)
        assertEquals(R.drawable.ic_profile_streak_sparks_5, ProfileFragment.resolveProfileHaloStyle(7)?.accentRes)

        assertEquals(R.drawable.ic_profile_streak_glow_25, ProfileFragment.resolveProfileHaloStyle(30)?.glowRes)
        assertEquals(R.drawable.ic_profile_streak_ring_25, ProfileFragment.resolveProfileHaloStyle(30)?.ringRes)
        assertEquals(R.drawable.ic_profile_streak_sparks_25, ProfileFragment.resolveProfileHaloStyle(30)?.accentRes)

        assertEquals(R.drawable.ic_profile_streak_glow_50, ProfileFragment.resolveProfileHaloStyle(60)?.glowRes)
        assertEquals(R.drawable.ic_profile_streak_ring_50, ProfileFragment.resolveProfileHaloStyle(60)?.ringRes)
        assertEquals(R.drawable.ic_profile_streak_sparks_50, ProfileFragment.resolveProfileHaloStyle(60)?.accentRes)
    }

    @Test
    fun `uses streak tier color for profile streak icon accent`() {
        assertEquals(0xFFF97316.toInt(), ProfileFragment.resolveStreakAccentColor(1))
        assertEquals(0xFFEAB308.toInt(), ProfileFragment.resolveStreakAccentColor(7))
        assertEquals(0xFFFFD166.toInt(), ProfileFragment.resolveStreakAccentColor(250))
    }
}
