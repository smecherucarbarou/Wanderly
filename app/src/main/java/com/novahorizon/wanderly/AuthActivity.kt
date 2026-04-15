package com.novahorizon.wanderly

import android.content.Context
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
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.databinding.ActivityAuthBinding
import io.github.jan.supabase.auth.auth
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
                try {
                    SupabaseClient.client.auth.importAuthToken(uri.toString())
                    navigateToMain()
                    return@launch
                } catch (e: Exception) {
                    Log.e("AuthActivity", "Failed to import auth callback (${e.javaClass.simpleName})")
                }
            }

            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val rememberMe = prefs.getBoolean(Constants.KEY_REMEMBER_ME, false)
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
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showAuthUi() {
        binding.authLoading.visibility = View.GONE
        binding.authNavHost.visibility = View.VISIBLE
    }

    private fun isAuthCallback(uri: Uri?): Boolean {
        return AuthCallbackMatcher.matches(uri?.scheme, uri?.host, uri?.path)
    }
}
