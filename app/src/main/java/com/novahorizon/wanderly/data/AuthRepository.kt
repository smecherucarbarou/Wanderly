package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.api.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

open class AuthRepository {

    data class SignUpResult(val emailAlreadyRegistered: Boolean)

    open suspend fun signInWithEmail(email: String, password: String) {
        SupabaseClient.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    open suspend fun signUpWithEmail(
        email: String,
        password: String,
        username: String
    ): SignUpResult {
        val result = SupabaseClient.client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            this.data = buildJsonObject {
                put("username", username)
            }
        }

        return SignUpResult(
            emailAlreadyRegistered = result != null && result.identities?.isEmpty() == true
        )
    }

    open suspend fun logout() {
        SupabaseClient.client.auth.signOut()
    }
}
