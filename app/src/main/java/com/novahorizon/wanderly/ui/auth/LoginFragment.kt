package com.novahorizon.wanderly.ui.auth

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.auth.SessionNavigator
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.ui.compose.screens.auth.LoginScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import dagger.hilt.android.AndroidEntryPoint
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : Fragment() {

    @Inject
    lateinit var repository: WanderlyRepository

    private val viewModel: AuthViewModel by viewModels()
    private var googleSignInInFlight = false

    private val authCallbackUrl by lazy(LazyThreadSafetyMode.NONE) {
        Constants.authCallbackUrl()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WanderlyTheme {
                    LoginScreen(
                        viewModel = viewModel,
                        onNavigateToSignup = {
                            findNavController().navigate(R.id.action_login_to_signup)
                        },
                        onGoogleSignIn = { initiateGoogleSignIn() },
                        onLoginSuccess = { rememberMe -> handleLoginSuccess(rememberMe) }
                    )
                }
            }
        }
    }

    private fun initiateGoogleSignIn() {
        if (googleSignInInFlight) return

        googleSignInInFlight = true
        viewModel.beginExternalSignIn()
        viewLifecycleOwner.lifecycleScope.launch {
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
                viewModel.externalSignInFailed(e)
            } catch (e: Exception) {
                googleSignInInFlight = false
                logDebug("Google OAuth launch failed: ${e.javaClass.simpleName}")
                viewModel.externalSignInFailed(e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!googleSignInInFlight) return

        viewLifecycleOwner.lifecycleScope.launch {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull(timeoutMs = 750L)
            if (!isAdded || !googleSignInInFlight) return@launch
            googleSignInInFlight = false
            if (session == null) {
                viewModel.resetExternalSignIn()
            } else {
                handleLoginSuccess(rememberMe = true)
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
        viewLifecycleOwner.lifecycleScope.launch {
            repository.setRememberMeEnabled(rememberMe)
            SessionNavigator.openMain(requireActivity())
        }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.d("LoginFragment", message)
        }
    }
}
