package com.novahorizon.wanderly

import com.novahorizon.wanderly.observability.AppLogger

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import com.novahorizon.wanderly.ui.auth.AuthViewModel
import com.novahorizon.wanderly.ui.auth.LoginRoute
import com.novahorizon.wanderly.ui.auth.SignupRoute
import com.novahorizon.wanderly.ui.compose.screens.auth.LoginScreen
import com.novahorizon.wanderly.ui.compose.screens.auth.SignupScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.Google
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AuthActivity : AppCompatActivity() {

    @Inject
    lateinit var repository: WanderlyRepository

    /** Activity-scoped so the Google-OAuth lifecycle (browser hop + [onResume] poll) shares it with the Login screen. */
    private val loginViewModel: AuthViewModel by viewModels()

    private var googleSignInInFlight = false

    private val authCallbackUrl by lazy(LazyThreadSafetyMode.NONE) {
        Constants.authCallbackUrl()
    }

    private val uiPhase = mutableStateOf(AuthUiPhase.Loading)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WanderlyTheme {
                when (uiPhase.value) {
                    AuthUiPhase.Loading -> AuthLoading()
                    AuthUiPhase.AuthUi -> AuthNavHost(
                        loginViewModel = loginViewModel,
                        onGoogleSignIn = { initiateGoogleSignIn() },
                        onLoginSuccess = { rememberMe -> handleLoginSuccess(rememberMe) }
                    )
                }
            }
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

    override fun onResume() {
        super.onResume()
        if (!googleSignInInFlight) return

        lifecycleScope.launch {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull(timeoutMs = 750L)
            if (!googleSignInInFlight) return@launch
            googleSignInInFlight = false
            if (session == null) {
                loginViewModel.resetExternalSignIn()
            } else {
                handleLoginSuccess(rememberMe = true)
            }
        }
    }

    private fun initiateGoogleSignIn() {
        if (googleSignInInFlight) return

        googleSignInInFlight = true
        loginViewModel.beginExternalSignIn()
        lifecycleScope.launch {
            try {
                repository.setRememberMeEnabled(true)
                val authUrl = SupabaseClient.client.auth.getOAuthUrl(
                    provider = Google,
                    redirectUrl = authCallbackUrl
                )
                launchOAuthUrl(authUrl)
                logDebug("Google OAuth browser launch requested")
            } catch (e: CancellationException) {
                googleSignInInFlight = false
                throw e
            } catch (e: ActivityNotFoundException) {
                googleSignInInFlight = false
                logDebug("No browser available for Google OAuth")
                loginViewModel.externalSignInFailed(e)
            } catch (e: Exception) {
                googleSignInInFlight = false
                logDebug("Google OAuth launch failed: ${e.javaClass.simpleName}")
                loginViewModel.externalSignInFailed(e)
            }
        }
    }

    private fun launchOAuthUrl(authUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        startActivity(intent)
    }

    private fun handleLoginSuccess(rememberMe: Boolean) {
        lifecycleScope.launch {
            repository.setRememberMeEnabled(rememberMe)
            SessionNavigator.openMain(this@AuthActivity)
        }
    }

    private fun navigateToMain() {
        SessionNavigator.openMain(this)
    }

    private fun showAuthUi() {
        uiPhase.value = AuthUiPhase.AuthUi
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

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d("AuthActivity", message)
        }
    }
}

private enum class AuthUiPhase { Loading, AuthUi }

@Composable
private fun AuthLoading() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AuthNavHost(
    loginViewModel: AuthViewModel,
    onGoogleSignIn: () -> Unit,
    onLoginSuccess: (rememberMe: Boolean) -> Unit
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = LoginRoute,
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        composable<LoginRoute> {
            LoginScreen(
                viewModel = loginViewModel,
                onNavigateToSignup = { navController.navigate(SignupRoute) },
                onGoogleSignIn = onGoogleSignIn,
                onLoginSuccess = onLoginSuccess
            )
        }
        composable<SignupRoute>(
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left) },
            popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right) }
        ) {
            val signupViewModel: AuthViewModel = hiltViewModel()
            SignupScreen(
                viewModel = signupViewModel,
                onNavigateToLogin = { navController.navigateUp() },
                onSignupSuccess = { navController.navigateUp() }
            )
        }
    }
}
