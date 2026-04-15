package com.novahorizon.wanderly.api

import android.content.Context
import com.novahorizon.wanderly.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.okhttp.OkHttp

object SupabaseClient {
    private var _client: SupabaseClient? = null
    val client: SupabaseClient
        get() = _client ?: throw IllegalStateException("SupabaseClient not initialized. Call init(context) first.")

    fun init(context: Context) {
        if (_client != null) return
        
        _client = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            httpEngine = OkHttp.create()
            install(Postgrest)
            install(Auth) {
                scheme = "wanderly"
                host = "login"
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
            }
            install(Realtime)
        }
    }
}
