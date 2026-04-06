package com.novahorizon.wanderly

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.databinding.ActivityAuthBinding
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            // Give Supabase a moment to load session from storage
            // In a real app, you'd wait for a specific initialization signal
            delay(500)

            val intent = intent
            val uri = intent.data
            
            // Check for deep link first
            if (uri != null && uri.scheme == "wanderly" && uri.host == "auth") {
                try {
                    SupabaseClient.client.auth.importAuthToken(uri.toString())
                    navigateToMain()
                    return@launch
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Check if session already exists
            val prefs = getSharedPreferences("WanderlyPrefs", Context.MODE_PRIVATE)
            val rememberMe = prefs.getBoolean("remember_me", false)
            val session = SupabaseClient.client.auth.currentSessionOrNull()

            if (session != null && rememberMe) {
                navigateToMain()
            } else {
                // Clear the session so user must log in again
                if (session != null && !rememberMe) {
                    try {
                        SupabaseClient.client.auth.signOut()
                    } catch (e: Exception) {
                        android.util.Log.e("Auth", "Sign out error: ${e.message}")
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
}
