package com.novahorizon.wanderly.ui

import com.novahorizon.wanderly.ui.common.AvatarLoader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarLoaderUrlPolicyTest {

    @Test
    fun `allows only https remote avatar urls`() {
        assertTrue(AvatarLoader.isRemoteAvatarUrlAllowed("https://example.com/avatar.jpg"))
        assertTrue(AvatarLoader.isRemoteAvatarUrlAllowed(" HTTPS://example.com/avatar.jpg "))
        assertFalse(AvatarLoader.isRemoteAvatarUrlAllowed("http://example.com/avatar.jpg"))
        assertFalse(AvatarLoader.isRemoteAvatarUrlAllowed("ftp://example.com/avatar.jpg"))
        assertFalse(AvatarLoader.isRemoteAvatarUrlAllowed("not-a-url"))
    }
}
