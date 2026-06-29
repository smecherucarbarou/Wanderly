package com.novahorizon.wanderly.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryPayloadTest {

    @Test
    fun `client payload contains only editable profile columns`() {
        val profile = Profile(
            id = "user-1",
            username = "Explorer",
            honey = 120,
            hive_rank = 2,
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

        val clientWritableFields = payload.javaClass.declaredFields
            .filter { !it.isSynthetic && !it.name.startsWith("$") }
            .map { it.name }.toSet()

        assertEquals(setOf("username", "avatar_url"), clientWritableFields)
        assertEquals("Explorer", payload.username)
        assertEquals("https://example.com/avatar.jpg", payload.avatar_url)
    }

    @Test
    fun `admin stats payload contains progress fields and excludes admin role`() {
        val payload = ProfileRepository.toAdminProfileStatsUpdate(
            honey = 375,
            streakCount = 9
        )

        assertEquals(375, payload.honey)
        assertEquals(9, payload.streak_count)
        assertEquals(3, payload.hive_rank)

        val adminWritableFields = payload.javaClass.declaredFields
            .filter { !it.isSynthetic && !it.name.startsWith("$") }
            .map { it.name }.toSet()
        assertEquals(setOf("honey", "streak_count", "hive_rank"), adminWritableFields)
    }

    @Test
    fun `admin stats payload clamps negative and absurd values`() {
        val negativePayload = ProfileRepository.toAdminProfileStatsUpdate(
            honey = -1,
            streakCount = -2
        )
        val cappedPayload = ProfileRepository.toAdminProfileStatsUpdate(
            honey = 2_000_000,
            streakCount = 10_000
        )

        assertEquals(0, negativePayload.honey)
        assertEquals(0, negativePayload.streak_count)
        assertEquals(1, negativePayload.hive_rank)
        assertEquals(1_000_000, cappedPayload.honey)
        assertEquals(3_650, cappedPayload.streak_count)
        assertEquals(4, cappedPayload.hive_rank)
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

    @Test
    fun `username rpc error codes map to typed profile errors`() {
        assertEquals(ProfileError.UsernameTaken, ProfileRepository.mapUsernameRpcErrorCode("username_taken"))
        assertEquals(ProfileError.InvalidUsername, ProfileRepository.mapUsernameRpcErrorCode("invalid_username"))
        assertEquals(ProfileError.Unauthenticated, ProfileRepository.mapUsernameRpcErrorCode("not_authenticated"))
        assertEquals(ProfileError.Unknown, ProfileRepository.mapUsernameRpcErrorCode("weird_code"))
    }

    @Test
    fun `detects postgrest schema cache errors`() {
        assertTrue(
            ProfileRepository.isPostgrestSchemaCacheError(
                IllegalStateException("PGRST002: could not find function in schema cache")
            )
        )
    }
}
