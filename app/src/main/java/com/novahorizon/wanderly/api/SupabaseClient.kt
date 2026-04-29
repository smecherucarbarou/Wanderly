package com.novahorizon.wanderly.api

import com.novahorizon.wanderly.observability.AppLogger

import android.content.Context
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

object SupabaseClient {
    private const val TAG = "SupabaseClient"
    private var _client: SupabaseClient? = null
    val client: SupabaseClient
        get() = _client ?: throw IllegalStateException("SupabaseClient not initialized. Call init(context) first.")

    fun init(context: Context) {
        if (_client != null) return

        validateConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "Initializing Supabase with URL: ${LogRedactor.redact(BuildConfig.SUPABASE_URL)}")
        }

        _client = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            httpEngine = OkHttp.create {
                config {
                    connectTimeout(60, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    writeTimeout(60, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }
            install(Postgrest)
            install(Auth) {
                scheme = Constants.AUTH_CALLBACK_SCHEME
                host = Constants.AUTH_CALLBACK_HOST
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
            }
            install(Realtime) {
                maxAttempts = 3
            }
        }
    }

    internal fun validateConfig(supabaseUrl: String, supabaseAnonKey: String) {
        val normalizedUrl = supabaseUrl.trim()
        val normalizedKey = supabaseAnonKey.trim()
        if (normalizedUrl.isBlank() || normalizedKey.isBlank()) {
            throw IllegalStateException("Supabase configuration is missing")
        }
        require(normalizedUrl.startsWith("https://")) {
            "Supabase URL must use HTTPS. Got: ${normalizedUrl.take(8)}..."
        }

        val usesPlaceholderValues = normalizedUrl.contains("your-supabase-url", ignoreCase = true) ||
            normalizedKey.contains("your-supabase-anon-key", ignoreCase = true)
        if (usesPlaceholderValues) {
            throw IllegalStateException("Supabase configuration is still using placeholder values")
        }
    }
}
