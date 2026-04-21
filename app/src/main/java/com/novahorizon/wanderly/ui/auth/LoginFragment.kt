package com.novahorizon.wanderly.ui.auth

import android.content.Context
import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.auth.SessionNavigator
import com.novahorizon.wanderly.databinding.FragmentLoginBinding
import com.novahorizon.wanderly.showSnackbar
import com.novahorizon.wanderly.ui.AuthViewModel
import com.novahorizon.wanderly.ui.WanderlyViewModelFactory

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: AuthViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyGraph.repository(requireContext()))
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

        binding.newBeeLink.setOnClickListener {
            findNavController().navigate(R.id.action_login_to_signup)
        }
    }

    private fun setupObservers() {
        viewModel.authState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AuthViewModel.AuthState.Loading -> {
                    binding.loginButton.isEnabled = false
                }
                is AuthViewModel.AuthState.Success -> {
                    // Save Remember Me preference
                    val isRememberMeChecked = binding.rememberMeCheckbox.isChecked
                    val prefs = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putBoolean(Constants.KEY_REMEMBER_ME, isRememberMeChecked).apply()

                    SessionNavigator.openMain(requireActivity())
                }
                is AuthViewModel.AuthState.Error -> {
                    binding.loginButton.isEnabled = true
                    showSnackbar(state.message, isError = true)
                }
                else -> {
                    binding.loginButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
