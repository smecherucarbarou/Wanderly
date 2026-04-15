package com.novahorizon.wanderly.auth

import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object AuthCallbackMatcher {
    fun matches(scheme: String?, host: String?, path: String?): Boolean {
        val validHost = host == Constants.AUTH_CALLBACK_HOST ||
            host == Constants.AUTH_CALLBACK_LEGACY_HOST

        return scheme == Constants.AUTH_CALLBACK_SCHEME &&
            validHost &&
            path == Constants.AUTH_CALLBACK_PATH
    }
}

object AuthRouting {
    fun shouldOpenMain(hasSession: Boolean, rememberMe: Boolean): Boolean = hasSession && rememberMe

    fun shouldStartSessionServices(hasSession: Boolean): Boolean = hasSession
}

object AuthSessionCoordinator {
    suspend fun awaitResolvedSessionOrNull(timeoutMs: Long = 5_000L): UserSession? {
        val auth = SupabaseClient.client.auth
        var session = auth.currentSessionOrNull()
        if (session != null) return session

        withTimeoutOrNull(timeoutMs) {
            auth.sessionStatus.first {
                it is SessionStatus.Authenticated || it is SessionStatus.NotAuthenticated
            }
        }

        session = auth.currentSessionOrNull()
        return session
    }
}
