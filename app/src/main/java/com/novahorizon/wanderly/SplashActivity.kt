package com.novahorizon.wanderly

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startAnimations()

        lifecycleScope.launch {
            delay(3000)
            checkAuthAndNavigate()
        }
    }

    private fun startAnimations() {
        val buzzyIn = ObjectAnimator.ofFloat(binding.buzzyMascot, View.TRANSLATION_Y, -500f, 0f)
        buzzyIn.duration = 1200
        buzzyIn.interpolator = OvershootInterpolator()

        val buzzyHover = ObjectAnimator.ofFloat(binding.buzzyMascot, View.TRANSLATION_Y, 0f, -20f, 0f)
        buzzyHover.duration = 1500
        buzzyHover.repeatCount = ObjectAnimator.INFINITE
        buzzyHover.interpolator = AccelerateDecelerateInterpolator()

        val logoScaleX = ObjectAnimator.ofFloat(binding.logo, View.SCALE_X, 0f, 1f)
        val logoScaleY = ObjectAnimator.ofFloat(binding.logo, View.SCALE_Y, 0f, 1f)
        val logoRotate = ObjectAnimator.ofFloat(binding.logo, View.ROTATION, -45f, 0f)

        val logoSet = AnimatorSet().apply {
            playTogether(logoScaleX, logoScaleY, logoRotate)
            duration = 1000
            startDelay = 400
        }

        val appNameFade = ObjectAnimator.ofFloat(binding.appNameText, View.ALPHA, 0f, 1f).apply {
            duration = 800
            startDelay = 1000
        }

        val taglineFade = ObjectAnimator.ofFloat(binding.tagline, View.ALPHA, 0f, 1f).apply {
            duration = 800
            startDelay = 1300
        }

        buzzyIn.start()
        buzzyHover.startDelay = 1200
        buzzyHover.start()
        logoSet.start()
        appNameFade.start()
        taglineFade.start()
    }

    private suspend fun checkAuthAndNavigate() {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
        if (session != null) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            startActivity(Intent(this, AuthActivity::class.java))
        }
        finish()
    }
}
