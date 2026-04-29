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
    fun `constants build canonical custom auth callback`() {
        val authCallbackUrl =
            "${Constants.AUTH_CALLBACK_SCHEME}://${Constants.AUTH_CALLBACK_HOST}${Constants.AUTH_CALLBACK_PATH}"

        assertEquals("wanderly://auth/callback", authCallbackUrl)
    }

    @Test
    fun `matches canonical custom auth callback shape`() {
        assertTrue(AuthCallbackMatcher.matches("wanderly", "auth", "/callback"))
    }

    @Test
    fun `accepts auth callback uri with token fragment or code query`() {
        assertTrue(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("wanderly://auth/callback#access_token=token&refresh_token=refresh")
            )
        )
        assertTrue(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("wanderly://auth/callback?code=auth-code")
            )
        )
    }

    @Test
    fun `rejects invite and old web callback shapes`() {
        assertFalse(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("wanderly://invite/ABC123")
            )
        )
        assertFalse(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("https://wanderly.app/auth/callback?code=auth-code")
            )
        )
        assertFalse(AuthCallbackMatcher.matches("wanderly", "login", "/callback"))
        assertFalse(AuthCallbackMatcher.matches("https", "wanderly.app", "/callback"))
        assertFalse(AuthCallbackMatcher.matches("https", "auth", "/auth/callback"))
        assertFalse(AuthCallbackMatcher.matches("https", "other", "/auth/callback"))
    }
}
