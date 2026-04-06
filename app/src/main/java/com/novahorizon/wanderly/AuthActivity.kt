package com.novahorizon.wanderly

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.databinding.ActivityAuthBinding
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Handle deep link from email
        val intent = intent
        val uri = intent.data
        if (uri != null && uri.scheme == "wanderly" && uri.host == "auth") {
            lifecycleScope.launch {
                try {
                    SupabaseClient.client.auth.importAuthToken(uri.toString())
                    startActivity(Intent(this@AuthActivity, MainActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
