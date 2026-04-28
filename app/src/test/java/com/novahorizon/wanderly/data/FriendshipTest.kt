package com.novahorizon.wanderly.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FriendshipTest {

    @Test
    fun `new friendship defaults to pending`() {
        val friendship = Friendship(user_id = "user-a", friend_id = "user-b")

        assertEquals("pending", friendship.status)
    }
}
