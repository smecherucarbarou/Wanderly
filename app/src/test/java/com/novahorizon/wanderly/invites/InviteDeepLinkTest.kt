package com.novahorizon.wanderly.invites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteDeepLinkTest {

    @Test
    fun `extractFriendCode accepts canonical invite link`() {
        val code = InviteDeepLink.extractFriendCode(
            scheme = "wanderly",
            host = "invite",
            pathSegments = listOf("ABC123")
        )

        assertEquals("ABC123", code)
    }

    @Test
    fun `extractFriendCode normalizes lowercase invite code`() {
        val code = InviteDeepLink.extractFriendCode(
            scheme = "wanderly",
            host = "invite",
            pathSegments = listOf("ab12cd")
        )

        assertEquals("AB12CD", code)
    }

    @Test
    fun `extractFriendCode accepts https invite route`() {
        val code = InviteDeepLink.extractFriendCode(
            scheme = "https",
            host = "wanderly.app",
            pathSegments = listOf("invite", "ABC123")
        )

        assertEquals("ABC123", code)
    }

    @Test
    fun `extractFriendCode accepts https invite query on homepage`() {
        val code = InviteDeepLink.extractFriendCode(
            scheme = "https",
            host = "wanderly.app",
            pathSegments = emptyList(),
            inviteQueryCode = "ab12cd"
        )

        assertEquals("AB12CD", code)
    }

    @Test
    fun `extractFriendCode rejects wrong host`() {
        val code = InviteDeepLink.extractFriendCode(
            scheme = "wanderly",
            host = "auth",
            pathSegments = listOf("ABC123")
        )

        assertNull(code)
    }

    @Test
    fun `extractFriendCode rejects missing code`() {
        val code = InviteDeepLink.extractFriendCode(
            scheme = "wanderly",
            host = "invite",
            pathSegments = emptyList()
        )

        assertNull(code)
    }

    @Test
    fun `extractFriendCode rejects extra path segments`() {
        val code = InviteDeepLink.extractFriendCode(
            scheme = "wanderly",
            host = "invite",
            pathSegments = listOf("ABC123", "extra")
        )

        assertNull(code)
    }

    @Test
    fun `extractFriendCode rejects malformed friend code`() {
        val code = InviteDeepLink.extractFriendCode(
            scheme = "wanderly",
            host = "invite",
            pathSegments = listOf("AB-123")
        )

        assertNull(code)
    }
}
