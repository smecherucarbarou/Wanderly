package com.novahorizon.wanderly.ui.auth

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
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.auth.AuthRouting
import com.novahorizon.wanderly.auth.SessionNavigator
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.compose.screens.auth.LoginScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import dagger.hilt.android.AndroidEntryPoint
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : Fragment() {

    @Inject
    lateinit var repository: WanderlyRepository

    private val viewModel: AuthViewModel by viewModels()

    private val authCallbackUrl by lazy(LazyThreadSafetyMode.NONE) {
        "${Constants.AUTH_CALLBACK_SCHEME}://${Constants.AUTH_CALLBACK_HOST}${Constants.AUTH_CALLBACK_PATH}"
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
                        onLoginSuccess = { handleLoginSuccess() }
                    )
                }
            }
        }
    }

    private fun initiateGoogleSignIn() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                repository.setRememberMeEnabled(true)
                com.novahorizon.wanderly.api.SupabaseClient.client.auth.signInWith(
                    provider = Google,
                    redirectUrl = authCallbackUrl
                )
            } catch (_: Exception) {
                // Error handled by AuthViewModel state
            }
        }
    }

    private fun handleLoginSuccess() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.setRememberMeEnabled(true)
            SessionNavigator.openMain(requireActivity())
        }
    }
}
