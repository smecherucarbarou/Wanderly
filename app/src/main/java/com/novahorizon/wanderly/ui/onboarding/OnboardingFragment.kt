package com.novahorizon.wanderly.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.compose.screens.onboarding.OnboardingScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.ui.main.MainNavCommand
import com.novahorizon.wanderly.ui.main.MainNavViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingFragment : Fragment() {

    @Inject
    lateinit var repository: WanderlyRepository

    private val mainNav: MainNavViewModel by activityViewModels()

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
            mainNav.send(MainNavCommand.AfterOnboarding)
        }
    }
}
