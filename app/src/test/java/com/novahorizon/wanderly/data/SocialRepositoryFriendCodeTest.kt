package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SocialRepositoryFriendCodeTest {

    @Test
    fun `normalize friend code uppercases valid values`() {
        assertEquals("ABC123", SocialRepository.normalizeFriendCode("abc123"))
    }

    @Test
    fun `normalize friend code rejects invalid shapes`() {
        assertNull(SocialRepository.normalizeFriendCode("abc12"))
        assertNull(SocialRepository.normalizeFriendCode("abc-12"))
    }

    @Test
    fun `social repository uses approved public profile rpcs instead of direct profiles public queries`() {
        val source = readSocialRepositorySource()

        assertFalse(source.contains("[\"profiles_public\"]"))
        assertTrue(source.contains("rpc(\"find_profile_by_friend_code\""))
        assertTrue(source.contains("rpc(\"get_accepted_friend_profiles\""))
        assertTrue(source.contains("rpc(\"get_social_leaderboard\""))
    }

    @Test
    fun `add friend returns generic error and logs redacted backend detail`() {
        val source = readSocialRepositorySource()
        val addFriend = source.substringAfter("suspend fun addFriendByCode")
            .substringBefore("suspend fun removeFriend")

        assertFalse(addFriend.contains("\"Failed to add friend: ${'$'}{e.message}\""))
        assertFalse(addFriend.contains("e.message}"))
        assertTrue(addFriend.contains("logError(\"Error adding friend\", e)"))
        assertTrue(addFriend.contains("AddFriendResult.Failure"))
        assertTrue(source.contains("Failure -> \"Could not add friend. Please try again.\""))
    }

    private fun readSocialRepositorySource(): String {
        val sourcePath = "app/src/main/java/com/novahorizon/wanderly/data/SocialRepository.kt"
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        val root = generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, sourcePath).isFile }
            ?: error("Could not find project root containing $sourcePath")

        return File(root, sourcePath).readText()
    }
}
