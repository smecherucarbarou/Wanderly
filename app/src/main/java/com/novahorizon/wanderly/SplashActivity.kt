package com.novahorizon.wanderly

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.novahorizon.wanderly.auth.AuthRouting
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.data.PreferencesStore
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.invites.InviteDeepLink
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    private var keepSplashOnScreen = true
    @Inject
    lateinit var preferencesStore: PreferencesStore
    @Inject
    lateinit var repository: WanderlyRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            cachePendingInviteIfPresent()
            checkAuthAndNavigate()
        }
    }

    private suspend fun checkAuthAndNavigate() {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
        val rememberMe = preferencesStore.isRememberMeEnabledSuspend()
        keepSplashOnScreen = false
        if (AuthRouting.shouldOpenMain(session != null, rememberMe)) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        } else {
            startActivity(
                Intent(this, AuthActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        }
        finish()
    }

    private suspend fun cachePendingInviteIfPresent() {
        val inviteCode = InviteDeepLink.extractFriendCode(intent?.data) ?: return
        repository.cachePendingInviteCode(inviteCode)
    }
}
