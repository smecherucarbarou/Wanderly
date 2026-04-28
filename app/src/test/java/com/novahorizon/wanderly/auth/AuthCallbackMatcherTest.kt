package com.novahorizon.wanderly.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCallbackMatcherTest {

    @Test
    fun matchesCanonicalCallback() {
        assertTrue(AuthCallbackMatcher.matches("https", "wanderly.app", "/auth/callback"))
    }

    @Test
    fun rejectsUnexpectedCallbackShape() {
        assertFalse(AuthCallbackMatcher.matches("wanderly", "auth", "/callback"))
        assertFalse(AuthCallbackMatcher.matches("wanderly", "login", "/callback"))
        assertFalse(AuthCallbackMatcher.matches("https", "wanderly.app", "/callback"))
        assertFalse(AuthCallbackMatcher.matches("https", "auth", "/auth/callback"))
        assertFalse(AuthCallbackMatcher.matches("https", "other", "/auth/callback"))
    }
}
