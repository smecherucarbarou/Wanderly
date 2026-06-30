package com.novahorizon.wanderly.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.ui.main.MainNavCommand
import com.novahorizon.wanderly.ui.main.MainNavViewModel
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
    private val mainNav: MainNavViewModel by activityViewModels()
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
                        onAcceptFriendRequest = { requesterId -> viewModel.acceptFriendRequest(requesterId) },
                        onRejectFriendRequest = { requesterId -> viewModel.rejectFriendRequest(requesterId) },
                        onBrowseMissions = { mainNav.send(MainNavCommand.ToMissions) },
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
            // QW-8: always load the social home, regardless of any pending deeplink invite.
            viewModel.loadSocialHome()

            // QW-11: a deeplinked friend code is confirmed by the user, never auto-submitted.
            val code = repository.consumePendingInviteCode()
            if (!code.isNullOrBlank()) {
                confirmDeeplinkFriendRequest(code)
            }
        }
    }

    private suspend fun confirmDeeplinkFriendRequest(friendCode: String) {
        val profile = repository.findProfileByFriendCode(friendCode)
        if (profile == null) {
            showSnackbar(getString(R.string.social_friend_code_not_found), isError = true)
            return
        }
        val username = profile.username ?: friendCode
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.social_add_friend_confirm_title)
            .setMessage(getString(R.string.social_add_friend_confirm_message, username))
            .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.addFriend(friendCode) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
