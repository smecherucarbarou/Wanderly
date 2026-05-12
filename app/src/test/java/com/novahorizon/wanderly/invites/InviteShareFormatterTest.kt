package com.novahorizon.wanderly.invites

import org.junit.Assert.assertEquals
import org.junit.Test

class InviteShareFormatterTest {

    @Test
    fun `buildInviteUrl returns HTTPS invite link`() {
        val url = InviteShareFormatter.buildInviteUrl(friendCode = "abc123")

        assertEquals("https://wanderly.ro/invite/ABC123", url)
    }

    @Test
    fun `formats share text with HTTPS invite link and friend code fallback`() {
        val message = InviteShareFormatter.format(friendCode = "ABC123")

        assertEquals(
            "Join me on Wanderly!\nhttps://wanderly.ro/invite/ABC123\nFriend code: ABC123",
            message
        )
    }
}
