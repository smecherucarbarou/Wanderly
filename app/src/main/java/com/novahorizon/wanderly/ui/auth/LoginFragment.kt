package com.novahorizon.wanderly.ui.auth

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.auth.AuthRouting
import com.novahorizon.wanderly.auth.SessionNavigator
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.databinding.FragmentLoginBinding
import com.novahorizon.wanderly.ui.common.showSnackbar
import dagger.hilt.android.AndroidEntryPoint
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
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
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()

        binding.loginButton.setOnClickListener {
            val email = binding.emailInput.text.toString()
            val password = binding.passwordInput.text.toString()

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                showSnackbar(getString(R.string.auth_invalid_email), isError = true)
                return@setOnClickListener
            }

            if (password.isEmpty()) {
                showSnackbar(getString(R.string.auth_password_required), isError = true)
                return@setOnClickListener
            }

            viewModel.login(email, password)
        }

        binding.googleSignInButton.setOnClickListener {
            binding.googleSignInButton.isEnabled = false
            val rememberMe = AuthRouting.rememberMeForOAuthStart(
                isRememberMeChecked = binding.rememberMeCheckbox.isChecked
            )

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    repository.setRememberMeEnabled(rememberMe)
                    com.novahorizon.wanderly.api.SupabaseClient.client.auth.signInWith(
                        provider = Google,
                        redirectUrl = authCallbackUrl
                    )
                } catch (e: Exception) {
                    binding.googleSignInButton.isEnabled = true
                    showSnackbar(
                        getString(R.string.auth_google_sign_in_failed),
                        isError = true
                    )
                }
            }
        }

        binding.newBeeLink.setOnClickListener {
            findNavController().navigate(R.id.action_login_to_signup)
        }
    }

    private fun setupObservers() {
        viewModel.authState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AuthViewModel.AuthState.Loading -> {
                    binding.loginButton.isEnabled = false
                    binding.googleSignInButton.isEnabled = false
                }
                is AuthViewModel.AuthState.Success -> {
                    val isRememberMeChecked = binding.rememberMeCheckbox.isChecked
                    viewLifecycleOwner.lifecycleScope.launch {
                        repository.setRememberMeEnabled(isRememberMeChecked)
                        SessionNavigator.openMain(requireActivity())
                    }
                }
                is AuthViewModel.AuthState.Error -> {
                    binding.loginButton.isEnabled = true
                    binding.googleSignInButton.isEnabled = true
                    showSnackbar(state.message, isError = true)
                }
                else -> {
                    binding.loginButton.isEnabled = true
                    binding.googleSignInButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
