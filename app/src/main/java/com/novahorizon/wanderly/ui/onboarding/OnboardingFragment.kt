package com.novahorizon.wanderly.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.MainNavigationDestinations
import com.novahorizon.wanderly.ui.compose.screens.onboarding.OnboardingScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingFragment : Fragment() {

    @Inject
    lateinit var repository: WanderlyRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WanderlyTheme {
                    OnboardingScreen(
                        onComplete = { completeOnboarding() }
                    )
                }
            }
        }
    }

    private fun completeOnboarding() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.setOnboardingSeen(true)
            val navController = findNavController()
            navController.graph.setStartDestination(
                MainNavigationDestinations.destinationAfterOnboarding(R.id.mapFragment)
            )
            navController.navigate(R.id.action_onboarding_to_map)
        }
    }
}
