package com.novahorizon.wanderly.deeplinks

import android.app.Application
import android.net.Uri
import com.novahorizon.wanderly.auth.AuthCallbackMatcher
import com.novahorizon.wanderly.invites.InviteDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeepLinkMalformedTest {

    @Test
    fun `AuthActivity ignores auth callback shape when required payload is missing`() {
        assertFalse(AuthCallbackMatcher.matchesCallbackUri(Uri.parse("wanderly://auth/callback")))
        assertFalse(AuthCallbackMatcher.matchesCallbackUri(Uri.parse("wanderly://login/callback")))
    }

    @Test
    fun `AuthActivity accepts auth callback with extra unexpected query params`() {
        val callback = Uri.parse("wanderly://auth/callback?code=auth-code&unexpected=value")

        assertTrue(AuthCallbackMatcher.matchesCallbackUri(callback))
    }

    @Test
    fun `AuthActivity rejects null host or null uri callbacks`() {
        assertFalse(AuthCallbackMatcher.matchesCallbackUri(null))
        assertFalse(
            AuthCallbackMatcher.matchesCallbackUri(
                Uri.parse("wanderly:///callback#access_token=token")
            )
        )
    }

    @Test
    fun `SplashActivity invite parser ignores missing required code`() {
        assertNull(InviteDeepLink.extractFriendCode(Uri.parse("wanderly://invite")))
        assertNull(InviteDeepLink.extractFriendCode(Uri.parse("https://wanderly.app/invite")))
    }

    @Test
    fun `SplashActivity invite parser rejects malformed host and path`() {
        assertNull(InviteDeepLink.extractFriendCode(Uri.parse("wanderly:///ABC123")))
        assertNull(InviteDeepLink.extractFriendCode(Uri.parse("wanderly://invite/ABC123/extra")))
    }

    @Test
    fun `SplashActivity invite parser accepts canonical code with extra query params`() {
        val code = InviteDeepLink.extractFriendCode(
            Uri.parse("wanderly://invite/abc123?unexpected=value")
        )

        assertEquals("ABC123", code)
    }
}
