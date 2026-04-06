package com.novahorizon.wanderly.api

import com.novahorizon.wanderly.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseClient {
    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    ) {
        install(Postgrest)
        install(Auth) {
            scheme = "wanderly"
            host = "login"
            // This enables automatic session persistence to disk
            alwaysAutoRefresh = true
            autoLoadFromStorage = true
        }
    }
}
