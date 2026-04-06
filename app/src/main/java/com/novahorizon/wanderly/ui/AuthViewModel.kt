package com.novahorizon.wanderly.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.data.WanderlyRepository
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AuthViewModel(private val repository: WanderlyRepository) : ViewModel() {

    private val _authState = MutableLiveData<AuthState>(AuthState.Idle)
    val authState: LiveData<AuthState> = _authState

    sealed class AuthState {
        object Idle : AuthState()
        object Loading : AuthState()
        object Success : AuthState()
        data class Error(val message: String) : AuthState()
    }

    fun login(email: String, pass: String) {
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                SupabaseClient.client.auth.signInWith(Email) {
                    this.email = email
                    this.password = pass
                }
                _authState.postValue(AuthState.Success)
            } catch (e: Exception) {
                _authState.postValue(AuthState.Error(e.message ?: "Login failed"))
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
                _authState.postValue(AuthState.Error(e.message ?: "Signup error"))
            }
        }
    }
}
