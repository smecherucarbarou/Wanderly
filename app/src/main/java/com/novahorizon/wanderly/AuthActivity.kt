package com.novahorizon.wanderly

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthCallbackMatcher
import com.novahorizon.wanderly.auth.AuthRouting
import com.novahorizon.wanderly.auth.SessionNavigator
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.databinding.ActivityAuthBinding
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            val uri = intent.data

            if (isAuthCallback(uri)) {
                SupabaseClient.client.handleDeeplinks(
                    intent = intent,
                    onSessionSuccess = {
                        navigateToMain()
                    },
                    onError = { error ->
                        Log.e("AuthActivity", "Failed to import auth callback (${error.javaClass.simpleName})")
                        lifecycleScope.launch {
                            resumeStandardAuthFlow()
                        }
                    }
                )
                return@launch
            }

            resumeStandardAuthFlow()
        }
    }

    private fun navigateToMain() {
        SessionNavigator.openMain(this)
    }

    private fun showAuthUi() {
        binding.authLoading.visibility = View.GONE
        binding.authNavHost.visibility = View.VISIBLE
    }

    private suspend fun resumeStandardAuthFlow() {
        val rememberMe = WanderlyGraph.repository(this@AuthActivity).isRememberMeEnabled()
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()

        if (AuthRouting.shouldOpenMain(session != null, rememberMe)) {
            navigateToMain()
        } else {
            if (session != null && !rememberMe) {
                try {
                    SupabaseClient.client.auth.signOut()
                } catch (e: Exception) {
                    Log.e("AuthActivity", "Sign out error: ${e.message}")
                }
            }
            showAuthUi()
        }
    }

    private fun isAuthCallback(uri: Uri?): Boolean {
        return AuthCallbackMatcher.matchesCallbackUri(uri)
    }
}
