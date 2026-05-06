package com.novahorizon.wanderly

import com.novahorizon.wanderly.observability.AppLogger

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthCallbackMatcher
import com.novahorizon.wanderly.auth.AuthRouting
import com.novahorizon.wanderly.auth.SessionNavigator
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AuthActivity : AppCompatActivity() {

    private lateinit var authNavHost: FragmentContainerView
    private lateinit var authLoading: ProgressBar

    @Inject
    lateinit var repository: WanderlyRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        authNavHost = FragmentContainerView(this).apply {
            id = R.id.auth_nav_host
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        root.addView(authNavHost)

        authLoading = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
            isIndeterminate = true
        }
        root.addView(authLoading)

        setContentView(root)

        if (savedInstanceState == null) {
            val navHostFragment = NavHostFragment.create(R.navigation.auth_nav_graph)
            supportFragmentManager.beginTransaction()
                .replace(R.id.auth_nav_host, navHostFragment)
                .setPrimaryNavigationFragment(navHostFragment)
                .commit()
        }

        lifecycleScope.launch {
            val uri = intent.data

            if (isAuthCallback(uri)) {
                SupabaseClient.client.handleDeeplinks(
                    intent = intent,
                    onSessionSuccess = {
                        navigateToMain()
                    },
                    onError = { error ->
                        AppLogger.e("AuthActivity", "Failed to import auth callback (${error.javaClass.simpleName})")
                        CrashReporter.recordNonFatal(
                            CrashEvent.AUTH_CALLBACK_IMPORT_FAILED,
                            error,
                            CrashKey.COMPONENT to "auth",
                            CrashKey.OPERATION to "callback_import"
                        )
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
        authLoading.visibility = View.GONE
        authNavHost.visibility = View.VISIBLE
    }

    private suspend fun resumeStandardAuthFlow() {
        val rememberMe = repository.isRememberMeEnabled()
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()

        if (AuthRouting.shouldOpenMain(session != null, rememberMe)) {
            navigateToMain()
        } else {
            if (session != null && !rememberMe) {
                try {
                    SupabaseClient.client.auth.signOut()
                } catch (e: Exception) {
                    CrashReporter.recordNonFatal(
                        CrashEvent.AUTH_SIGN_OUT_FAILED,
                        e,
                        CrashKey.COMPONENT to "auth",
                        CrashKey.OPERATION to "fallback_sign_out"
                    )
                    if (BuildConfig.DEBUG) {
                        AppLogger.e("AuthActivity", "Sign out error: ${LogRedactor.redact(e.message)}")
                    } else {
                        AppLogger.e("AuthActivity", "Sign out error")
                    }
                }
            }
            showAuthUi()
        }
    }

    private fun isAuthCallback(uri: Uri?): Boolean {
        return AuthCallbackMatcher.matchesCallbackUri(uri)
    }
}
