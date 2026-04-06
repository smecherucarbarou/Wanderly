package com.novahorizon.wanderly.ui.auth

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.databinding.FragmentSignupBinding
import com.novahorizon.wanderly.showSnackbar
import com.novahorizon.wanderly.ui.AuthViewModel
import com.novahorizon.wanderly.ui.WanderlyViewModelFactory

class SignupFragment : Fragment() {

    private var _binding: FragmentSignupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyRepository(requireContext()))
    }

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
                showSnackbar("Invalid email format", isError = true)
                return@setOnClickListener
            }

            if (password.length < 8 || !password.any { it.isDigit() }) {
                showSnackbar("Password must be 8+ chars & contain a number", isError = true)
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                showSnackbar("Passwords do not match", isError = true)
                return@setOnClickListener
            }

            if (username.length < 3) {
                showSnackbar("Username must be 3+ chars", isError = true)
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
                    showSnackbar("Welcome to the Hive!", isError = false)
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
