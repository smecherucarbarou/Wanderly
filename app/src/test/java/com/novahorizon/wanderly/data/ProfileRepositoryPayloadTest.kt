package com.novahorizon.wanderly.data

import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryPayloadTest {

    @Test
    fun `client payload excludes server owned reward progress location and admin fields`() {
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

        val clientWritableFields = payload.javaClass.declaredFields.map { it.name }.toSet()
        val serverOwnedFields = setOf(
            "username",
            "honey",
            "hive_rank",
            "admin_role",
            "last_mission_date",
            "last_buzz_date",
            "last_lat",
            "last_lng",
            "streak_count"
        )

        assertTrue(clientWritableFields.none { it in serverOwnedFields })
    }

    @Test
    fun `profile repository never creates profiles from Android client`() {
        val source = projectFile("app/src/main/java/com/novahorizon/wanderly/data/ProfileRepository.kt").readText()

        assertFalse(source.contains(".insert("))
        assertFalse(source.contains(".upsert("))
        assertFalse(source.contains("create_profile_if_missing"))
        assertFalse(source.contains("ClientProfileInsert"))
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

        val adminWritableFields = payload.javaClass.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("honey", "streak_count", "hive_rank"), adminWritableFields)
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

    private fun projectFile(relativePath: String): File = File(projectRoot(), relativePath)

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }
}
