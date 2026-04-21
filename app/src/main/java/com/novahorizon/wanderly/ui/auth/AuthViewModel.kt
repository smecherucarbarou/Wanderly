package com.novahorizon.wanderly.ui.auth

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.api.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AuthViewModel : ViewModel() {

    private val _authState = MutableLiveData<AuthState>(AuthState.Idle)
    val authState: LiveData<AuthState> = _authState

    sealed class AuthState {
        object Idle : AuthState()
        object Loading : AuthState()
        object Success : AuthState()
        data class Error(val message: String) : AuthState()
    }

    private fun friendlyAuthError(raw: String?): String {
        val msg = raw?.lowercase() ?: return "Something went wrong. Please try again."
        return when {
            msg.contains("invalid login credentials")          -> "Incorrect email or password."
            msg.contains("email not confirmed")                -> "Please verify your email before signing in."
            msg.contains("user already registered")
                || msg.contains("already registered")
                || msg.contains("email already")               -> "An account with this email already exists."
            msg.contains("password should be at least")        -> "Password must be at least 6 characters."
            msg.contains("unable to validate email")
                || msg.contains("invalid email")               -> "Please enter a valid email address."
            msg.contains("network") || msg.contains("timeout")
                || msg.contains("connect")                     -> "No internet connection. Please check your network."
            msg.contains("rate limit") || msg.contains("too many") -> "Too many attempts. Please wait a moment and try again."
            msg.contains("weak password")                      -> "Password is too weak. Use letters, numbers and symbols."
            else                                               -> "Authentication failed. Please try again."
        }
    }

    fun login(email: String, pass: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                WanderlyGraph.emailAuthService().signInWithEmail(email, pass)
                _authState.postValue(AuthState.Success)
            } catch (e: Exception) {
                _authState.postValue(AuthState.Error(friendlyAuthError(e.message)))
            }
        }
    }

    fun signup(email: String, pass: String, username: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                // Trimitem username-ul ca user_metadata
                val result = SupabaseClient.client.auth.signUpWith(Email) {
                    this.email = email
                    this.password = pass
                    this.data = buildJsonObject {
                        put("username", username)
                    }
                }

                if (result != null && result.identities?.isEmpty() == true) {
                    _authState.postValue(AuthState.Error("Email already registered"))
                    return@launch
                }

                // Daca inregistrarea a reusit, consideram succes. 
                // Baza de date (prin Trigger) va crea profilul folosind 'username' din data.
                _authState.postValue(AuthState.Success)
            } catch (e: Exception) {
                _authState.postValue(AuthState.Error(friendlyAuthError(e.message)))
            }
        }
    }
}
