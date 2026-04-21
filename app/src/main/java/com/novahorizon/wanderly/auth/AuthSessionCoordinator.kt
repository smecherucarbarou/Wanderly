package com.novahorizon.wanderly.auth

import com.novahorizon.wanderly.api.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
