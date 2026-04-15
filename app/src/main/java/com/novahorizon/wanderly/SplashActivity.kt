package com.novahorizon.wanderly

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.databinding.ActivitySplashBinding
import io.github.jan.supabase.auth.auth

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startAnimations()

        Handler(Looper.getMainLooper()).postDelayed({
            checkAuthAndNavigate()
        }, 3000)
    }

    private fun startAnimations() {
        // 1. Buzzy Mascot Animation (Flying in and hovering)
        val buzzyIn = ObjectAnimator.ofFloat(binding.buzzyMascot, View.TRANSLATION_Y, -500f, 0f)
        buzzyIn.duration = 1200
        buzzyIn.interpolator = OvershootInterpolator()

        val buzzyHover = ObjectAnimator.ofFloat(binding.buzzyMascot, View.TRANSLATION_Y, 0f, -20f, 0f)
        buzzyHover.duration = 1500
        buzzyHover.repeatCount = ObjectAnimator.INFINITE
        buzzyHover.interpolator = AccelerateDecelerateInterpolator()

        // 2. Logo Scale & Rotate
        val logoScaleX = ObjectAnimator.ofFloat(binding.logo, View.SCALE_X, 0f, 1f)
        val logoScaleY = ObjectAnimator.ofFloat(binding.logo, View.SCALE_Y, 0f, 1f)
        val logoRotate = ObjectAnimator.ofFloat(binding.logo, View.ROTATION, -45f, 0f)
        
        val logoSet = AnimatorSet()
        logoSet.playTogether(logoScaleX, logoScaleY, logoRotate)
        logoSet.duration = 1000
        logoSet.startDelay = 400

        // 3. Text Fading
        val appNameFade = ObjectAnimator.ofFloat(binding.appNameText, View.ALPHA, 0f, 1f)
        appNameFade.duration = 800
        appNameFade.startDelay = 1000

        val taglineFade = ObjectAnimator.ofFloat(binding.tagline, View.ALPHA, 0f, 1f)
        taglineFade.duration = 800
        taglineFade.startDelay = 1300

        // Start everything
        buzzyIn.start()
        buzzyHover.startDelay = 1200
        buzzyHover.start()
        logoSet.start()
        appNameFade.start()
        taglineFade.start()
    }

    private fun checkAuthAndNavigate() {
        val session = SupabaseClient.client.auth.currentSessionOrNull()
        if (session != null) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            startActivity(Intent(this, AuthActivity::class.java))
        }
        finish()
    }
}
