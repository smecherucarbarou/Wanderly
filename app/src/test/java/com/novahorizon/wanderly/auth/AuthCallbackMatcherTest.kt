package com.novahorizon.wanderly.auth

import android.app.Application
import android.net.Uri
import com.novahorizon.wanderly.Constants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AuthCallbackMatcherTest {

    @Test
    fun `constants build canonical HTTPS auth callback`() {
        val authCallbackUrl = Constants.authCallbackUrl()

        assertEquals("https://wanderly.ro/auth/callback", authCallbackUrl)
    }

    @Test
    fun `matches HTTPS auth callback shape`() {
        assertTrue(AuthCallbackMatcher.matches("https", "wanderly.ro", "/auth/callback"))
    }

    @Test
    fun `rejects legacy custom auth callback shape`() {
        assertFalse(AuthCallbackMatcher.matches("wanderly", "auth", "/callback"))
    }

    @Test
    fun `accepts valid HTTPS callback with code query param`() {
        assertTrue(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("https://wanderly.ro/auth/callback?code=auth-code")
            )
        )
    }

    @Test
    fun `rejects legacy callback even with valid code query param`() {
        assertFalse(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("wanderly://auth/callback?code=auth-code")
            )
        )
    }

    @Test
    fun `rejects callback with access_token in fragment`() {
        assertFalse(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("https://wanderly.ro/auth/callback#access_token=token&refresh_token=refresh")
            )
        )
    }

    @Test
    fun `rejects legacy callback with access_token in fragment`() {
        assertFalse(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("wanderly://auth/callback#access_token=token&refresh_token=refresh")
            )
        )
    }

    @Test
    fun `rejects callback with access_token in query`() {
        assertFalse(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("https://wanderly.ro/auth/callback?access_token=abc")
            )
        )
    }

    @Test
    fun `rejects callback with refresh_token in query`() {
        assertFalse(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("https://wanderly.ro/auth/callback?refresh_token=abc")
            )
        )
    }

    @Test
    fun `rejects callback without code param`() {
        assertFalse(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("https://wanderly.ro/auth/callback")
            )
        )
    }

    @Test
    fun `rejects invite and wrong host shapes`() {
        assertFalse(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("wanderly://invite/ABC123")
            )
        )
        assertFalse(AuthCallbackMatcher.matches("wanderly", "login", "/callback"))
        assertFalse(AuthCallbackMatcher.matches("https", "wanderly.ro", "/callback"))
        assertFalse(AuthCallbackMatcher.matches("https", "auth", "/auth/callback"))
        assertFalse(AuthCallbackMatcher.matches("https", "other", "/auth/callback"))
    }
}
