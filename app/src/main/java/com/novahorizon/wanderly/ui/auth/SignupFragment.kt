package com.novahorizon.wanderly.ui.auth

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.databinding.FragmentSignupBinding
import com.novahorizon.wanderly.ui.common.showSnackbar
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SignupFragment : Fragment() {

    private var _binding: FragmentSignupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()

        binding.signupButton.setOnClickListener {
            val email = binding.emailInput.text.toString()
            val username = binding.usernameInput.text.toString()
            val password = binding.passwordInput.text.toString()
            val confirmPassword = binding.confirmPasswordInput.text.toString()

            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                showSnackbar(getString(R.string.auth_invalid_email), isError = true)
                return@setOnClickListener
            }

            if (password.length < 8 || !password.any { it.isDigit() }) {
                showSnackbar(getString(R.string.auth_password_requirements), isError = true)
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                showSnackbar(getString(R.string.auth_passwords_do_not_match), isError = true)
                return@setOnClickListener
            }

            if (username.length < 3) {
                showSnackbar(getString(R.string.auth_username_minimum), isError = true)
                return@setOnClickListener
            }

            viewModel.signup(email, password, username)
        }

        binding.alreadyBeeLink.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupObservers() {
        viewModel.authState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AuthViewModel.AuthState.Loading -> {
                    binding.signupButton.isEnabled = false
                }
                is AuthViewModel.AuthState.Success -> {
                    showSnackbar(getString(R.string.auth_signup_success), isError = false)
                    findNavController().navigateUp()
                }
                is AuthViewModel.AuthState.Error -> {
                    binding.signupButton.isEnabled = true
                    showSnackbar(state.message, isError = true)
                }
                else -> {
                    binding.signupButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
