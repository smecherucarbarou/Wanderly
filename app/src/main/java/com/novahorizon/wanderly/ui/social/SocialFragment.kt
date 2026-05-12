package com.novahorizon.wanderly.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.invites.InviteShareFormatter
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.ui.compose.screens.social.SocialScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SocialFragment : Fragment() {

    private val viewModel: SocialViewModel by viewModels()
    @Inject
    lateinit var repository: WanderlyRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WanderlyTheme {
                    SocialScreen(
                        viewModel = viewModel,
                        onAddFriend = { code -> viewModel.addFriend(code) },
                        onBrowseMissions = { findNavController().navigate(R.id.missionsFragment) },
                        onCopyCode = { code -> copyFriendCode(code) },
                        onShareCode = { code -> shareFriendCode(code) }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.addFriendResult.observe(viewLifecycleOwner) { result ->
            result?.let {
                val message = it.message.asString(requireContext())
                showSnackbar(message, isError = it.isError)
                viewModel.clearAddFriendResult()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val pendingInviteCode = repository.peekPendingInviteCode()
            if (pendingInviteCode.isNullOrBlank()) {
                viewModel.loadLeaderboard()
            } else {
                val code = repository.consumePendingInviteCode()
                if (code != null) {
                    viewModel.addFriend(code)
                }
            }
        }
    }

    private fun copyFriendCode(friendCode: String) {
        val clipboardManager = requireContext().getSystemService(ClipboardManager::class.java)
        clipboardManager?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.profile_friend_code_clip_label), friendCode)
        )
        showSnackbar(getString(R.string.friend_code_copied), isError = false)
    }

    private fun shareFriendCode(friendCode: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, InviteShareFormatter.format(friendCode))
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.profile_share_friend_code)))
    }
}
