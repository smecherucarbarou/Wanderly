package com.novahorizon.wanderly.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LogRedactorTest {

    @Test
    fun redactHidesUrlsAndQuerySecrets() {
        val input = "Failed url=https://project.supabase.co/rest/v1/profiles?apikey=secret123"

        val redacted = LogRedactor.redact(input)

        assertEquals("Failed url=[redacted-url]", redacted)
        assertFalse(redacted.contains("project.supabase.co"))
        assertFalse(redacted.contains("secret123"))
    }

    @Test
    fun redactHidesBearerTokensAndJwtShapedValues() {
        val input = "Authorization: Bearer abc.def.ghi token=eyJhbGciOiJIUzI1NiJ9.payload.signature"

        val redacted = LogRedactor.redact(input)

        assertEquals("Authorization: Bearer [redacted-token] token=[redacted-jwt]", redacted)
    }

    @Test
    fun redactHidesEmailsAndLocalFileUris() {
        val input = "User user@example.com selected file:///data/user/0/app/cache/avatar.jpg"

        val redacted = LogRedactor.redact(input)

        assertEquals("User [redacted-email] selected [redacted-uri]", redacted)
    }

    @Test
    fun redactHidesUuidIdentifiers() {
        val input = "profile=123e4567-e89b-12d3-a456-426614174000"

        val redacted = LogRedactor.redact(input)

        assertEquals("profile=[redacted-id]", redacted)
    }

    @Test
    fun redactBoundsLongValues() {
        val redacted = LogRedactor.redact("x".repeat(400))

        assertEquals(203, redacted.length)
        assertEquals("...", redacted.takeLast(3))
    }
}
