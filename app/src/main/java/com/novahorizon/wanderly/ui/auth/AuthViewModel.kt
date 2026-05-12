package com.novahorizon.wanderly.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    /** Persistent UI state that survives configuration changes. */
    sealed interface AuthUiState {
        data object Idle : AuthUiState
        data object Loading : AuthUiState
    }

    /** One-shot events that should be consumed exactly once. */
    sealed interface AuthEvent {
        data object LoginSuccess : AuthEvent
        data object SignupSuccess : AuthEvent
        data class Error(val message: String) : AuthEvent
    }

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events: Flow<AuthEvent> = _events.receiveAsFlow()

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

    fun setLoading() {
        _uiState.value = AuthUiState.Loading
    }

    fun setError(message: String) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Idle
            _events.send(AuthEvent.Error(message))
        }
    }

    fun beginExternalSignIn() {
        _uiState.value = AuthUiState.Loading
    }

    fun resetExternalSignIn() {
        _uiState.value = AuthUiState.Idle
    }

    fun externalSignInFailed(error: Throwable? = null) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Idle
            _events.send(AuthEvent.Error("Google sign-in failed. Please try again."))
        }
    }

    fun login(email: String, pass: String) {
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            try {
                authRepository.signInWithEmail(email, pass)
                _uiState.value = AuthUiState.Idle
                _events.send(AuthEvent.LoginSuccess)
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Idle
                _events.send(AuthEvent.Error(friendlyAuthError(e.message)))
            }
        }
    }

    fun signup(email: String, pass: String, username: String) {
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            try {
                val result = authRepository.signUpWithEmail(email, pass, username)
                if (result.emailAlreadyRegistered) {
                    _uiState.value = AuthUiState.Idle
                    _events.send(AuthEvent.Error("Email already registered"))
                    return@launch
                }

                _uiState.value = AuthUiState.Idle
                _events.send(AuthEvent.SignupSuccess)
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Idle
                _events.send(AuthEvent.Error(friendlyAuthError(e.message)))
            }
        }
    }
}
