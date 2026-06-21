package com.novahorizon.wanderly.ui.guide

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.auth.SessionNavigator
import com.novahorizon.wanderly.ui.common.LocationPermissionController
import com.novahorizon.wanderly.ui.common.LocationPermissionGate
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.ui.compose.screens.guide.WanderlyGuideScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WanderlyGuideFragment : Fragment() {

    private val viewModel: WanderlyGuideViewModel by viewModels()
    private val locationPermissionController = LocationPermissionController(this)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WanderlyTheme {
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    WanderlyGuideScreen(
                        state = state,
                        onBack = { findNavController().popBackStack() },
                        onLogin = { SessionNavigator.openAuth(requireActivity()) },
                        onSendMessage = ::sendMessageWithLocationIfNeeded,
                        onRetry = viewModel::refreshEntitlement,
                        showDebugPlusNote = BuildConfig.DEBUG
                    )
                }
            }
        }
    }

    private fun sendMessageWithLocationIfNeeded(message: String) {
        if (!guideMessageNeedsLocationContext(message)) {
            viewModel.sendMessage(message)
            return
        }

        when (val permissionState = locationPermissionController.resolveState()) {
            LocationPermissionGate.State.GRANTED -> viewModel.sendMessage(message)
            LocationPermissionGate.State.REQUEST,
            LocationPermissionGate.State.RATIONALE -> {
                if (permissionState == LocationPermissionGate.State.RATIONALE && isAdded) {
                    showSnackbar(getString(R.string.wanderly_guide_location_permission_rationale), isError = true)
                }
                locationPermissionController.requestPermission {
                    if (isAdded) {
                        if (it) {
                            viewModel.sendMessage(message)
                        } else {
                            showSnackbar(getString(R.string.wanderly_guide_location_permission_required), isError = true)
                        }
                    }
                }
            }

            LocationPermissionGate.State.SETTINGS -> {
                showSnackbar(getString(R.string.wanderly_guide_location_permission_settings), isError = true)
                viewModel.sendMessage(message)
            }
        }
    }
}
