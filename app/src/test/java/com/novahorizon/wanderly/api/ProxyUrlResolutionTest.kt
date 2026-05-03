package com.novahorizon.wanderly.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyUrlResolutionTest {

    @Test
    fun `gemini proxy url defaults to supabase edge function url`() {
        assertEquals(
            "https://project.supabase.co/functions/v1/gemini-proxy",
            GeminiClient.resolveProxyUrl(
                configuredProxyUrl = "",
                supabaseUrl = "https://project.supabase.co/"
            )
        )
    }

    @Test
    fun `gemini proxy url uses explicit override`() {
        assertEquals(
            "https://proxy.example.com/gemini",
            GeminiClient.resolveProxyUrl(
                configuredProxyUrl = " https://proxy.example.com/gemini ",
                supabaseUrl = "https://project.supabase.co"
            )
        )
    }

    @Test
    fun `places proxy url defaults to supabase edge function url`() {
        assertEquals(
            "https://project.supabase.co/functions/v1/google-places-proxy",
            PlacesProxyClient.resolveProxyUrl(
                configuredProxyUrl = "",
                supabaseUrl = "https://project.supabase.co/"
            )
        )
    }

    @Test
    fun `places proxy url uses explicit override`() {
        assertEquals(
            "https://proxy.example.com/places",
            PlacesProxyClient.resolveProxyUrl(
                configuredProxyUrl = " https://proxy.example.com/places ",
                supabaseUrl = "https://project.supabase.co"
            )
        )
    }
}
