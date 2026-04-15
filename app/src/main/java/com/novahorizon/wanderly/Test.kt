package com.novahorizon.wanderly

import com.novahorizon.wanderly.api.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email

suspend fun test() {
    val result = SupabaseClient.client.auth.signUpWith(Email) {
        email = "test@test.com"
        password = "password"
    }
    val identities = result?.identities
}
