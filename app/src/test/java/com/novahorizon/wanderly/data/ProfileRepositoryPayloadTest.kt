package com.novahorizon.wanderly.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileRepositoryPayloadTest {

    @Test
    fun `client payload excludes admin role`() {
        val profile = Profile(
            id = "user-1",
            username = "Explorer",
            honey = 120,
            hive_rank = 2,
            admin_role = true,
            badges = listOf("Scout Bee"),
            cities_visited = listOf("Bucharest"),
            avatar_url = "https://example.com/avatar.jpg",
            last_mission_date = "2026-04-20",
            last_lat = 44.43,
            last_lng = 26.10,
            friend_code = "ABC123",
            streak_count = 7,
            explorer_class = "ADVENTURER"
        )

        val payload = ProfileRepository.toClientProfileUpdate(profile)

        assertEquals("Explorer", payload.username)
        assertEquals(120, payload.honey)
        assertEquals(7, payload.streak_count)
        assertFalse(payload.javaClass.declaredFields.any { it.name == "admin_role" })
    }

    @Test
    fun `normalizes legacy supabase avatar urls to storage paths for persistence`() {
        assertEquals(
            "profiles/user-1/avatar-123.jpg",
            ProfileRepository.normalizeAvatarUrl(
                "https://aimparysgwabameqjgsv.supabase.co/storage/v1/object/avatars/profiles/user-1/avatar-123.jpg"
            )
        )
        assertEquals(
            "profiles/user-1/avatar-123.jpg",
            ProfileRepository.normalizeAvatarUrl(
                "https://aimparysgwabameqjgsv.supabase.co/storage/v1/object/public/avatars/profiles/user-1/avatar-123.jpg?download=1"
            )
        )
    }

    @Test
    fun `keeps unrelated avatar urls untouched when normalizing`() {
        assertEquals(
            "https://example.com/avatar.jpg",
            ProfileRepository.normalizeAvatarUrl("https://example.com/avatar.jpg")
        )
        assertNull(ProfileRepository.normalizeAvatarUrl(null))
    }
}
