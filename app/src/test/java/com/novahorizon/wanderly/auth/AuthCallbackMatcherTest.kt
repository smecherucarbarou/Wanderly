package com.novahorizon.wanderly.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCallbackMatcherTest {

    @Test
    fun matchesCanonicalCallback() {
        assertTrue(AuthCallbackMatcher.matches("wanderly", "auth", "/callback"))
    }

    @Test
    fun matchesLegacyCallbackHost() {
        assertTrue(AuthCallbackMatcher.matches("wanderly", "login", "/callback"))
    }

    @Test
    fun rejectsUnexpectedCallbackShape() {
        assertFalse(AuthCallbackMatcher.matches("https", "auth", "/callback"))
        assertFalse(AuthCallbackMatcher.matches("wanderly", "auth", "/wrong"))
        assertFalse(AuthCallbackMatcher.matches("wanderly", "other", "/callback"))
    }
}
