package com.novahorizon.wanderly.api

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SupabaseClientConfigTest {

    @Test
    fun `validate config accepts non-placeholder values`() {
        SupabaseClient.validateConfig(
            supabaseUrl = "https://example.supabase.co",
            supabaseAnonKey = "real-anon-key"
        )
    }

    @Test
    fun `validate config rejects blank values`() {
        assertFailsWithMessage("Supabase configuration is missing")
        assertFailsWithMessage("Supabase configuration is missing", "https://example.supabase.co", "")
    }

    @Test
    fun `validate config rejects template placeholders`() {
        assertFailsWithMessage(
            expectedMessage = "Supabase configuration is still using placeholder values",
            supabaseUrl = "https://your-supabase-url.supabase.co",
            supabaseAnonKey = "your-supabase-anon-key"
        )
    }

    private fun assertFailsWithMessage(
        expectedMessage: String,
        supabaseUrl: String = "",
        supabaseAnonKey: String = "key"
    ) {
        try {
            SupabaseClient.validateConfig(supabaseUrl, supabaseAnonKey)
            fail("Expected validateConfig to throw")
        } catch (error: IllegalStateException) {
            assertEquals(expectedMessage, error.message)
        }
    }
}
