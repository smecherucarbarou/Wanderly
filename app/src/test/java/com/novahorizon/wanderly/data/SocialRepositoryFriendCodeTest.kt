package com.novahorizon.wanderly.data

import org.junit.Assert.assertEquals
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
}
