package com.novahorizon.wanderly.ui.social

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.common.AvatarLoader
import com.novahorizon.wanderly.ui.common.RankUiFormatter
import com.novahorizon.wanderly.ui.common.showSnackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SocialFragment : Fragment() {

    private val viewModel: SocialViewModel by viewModels()
    @Inject
    lateinit var repository: WanderlyRepository
    
    private lateinit var socialTabs: TabLayout
    private lateinit var socialRecycler: RecyclerView
    private lateinit var addFriendLayout: View
    private lateinit var friendCodeInput: EditText
    private lateinit var addFriendButton: View
    private lateinit var loadingIndicator: View
    private lateinit var emptyStateText: TextView
    private var formattingFriendCode = false

    private val socialAdapter = SocialAdapter(
        onRemoveClick = { profile -> showRemoveFriendDialog(profile) },
        onAcceptClick = { profile -> viewModel.acceptFriendRequest(profile.id) },
        onRejectClick = { profile -> viewModel.rejectFriendRequest(profile.id) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_social, container, false)
        socialTabs = view.findViewById(R.id.social_tabs)
        socialRecycler = view.findViewById(R.id.social_recycler)
        addFriendLayout = view.findViewById(R.id.add_friend_layout)
        friendCodeInput = view.findViewById(R.id.friend_username_input)
        addFriendButton = view.findViewById(R.id.add_friend_button)
        loadingIndicator = view.findViewById(R.id.social_loading)
        emptyStateText = view.findViewById(R.id.social_empty_state)
        
        socialRecycler.layoutManager = LinearLayoutManager(requireContext())
        socialRecycler.adapter = socialAdapter
        friendCodeInput.doAfterTextChanged { editable ->
            if (formattingFriendCode) return@doAfterTextChanged
            val current = editable?.toString().orEmpty()
            val normalized = current.uppercase(Locale.US)
            if (current != normalized) {
                formattingFriendCode = true
                friendCodeInput.setText(normalized)
                friendCodeInput.setSelection(normalized.length)
                formattingFriendCode = false
            }
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupObservers()

        socialTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> { // Leaderboard
                        addFriendLayout.visibility = View.GONE
                        viewModel.loadLeaderboard()
                        renderEmptyState(
                            profiles = viewModel.leaderboard.value.orEmpty(),
                            emptyMessage = R.string.social_empty_leaderboard
                        )
                    }
                    1 -> { // Friends
                        addFriendLayout.visibility = View.VISIBLE
                        viewModel.loadFriends()
                        renderEmptyState(
                            profiles = viewModel.friends.value.orEmpty(),
                            emptyMessage = R.string.social_empty_friends
                        )
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        addFriendButton.setOnClickListener {
            val friendCode = friendCodeInput.text.toString().trim().uppercase(Locale.US)
            if (friendCode.isNotEmpty()) {
                viewModel.addFriend(friendCode)
                friendCodeInput.setText("")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val pendingInviteCode = repository.peekPendingInviteCode()
            if (pendingInviteCode.isNullOrBlank()) {
                viewModel.loadLeaderboard()
            } else {
                socialTabs.getTabAt(1)?.select()
                applyPendingInviteCode()
            }
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderSocialState(state)
                }
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            if (isLoading) {
                emptyStateText.visibility = View.GONE
                socialRecycler.visibility = View.VISIBLE
            }
        }

        viewModel.leaderboard.observe(viewLifecycleOwner) { profiles ->
            if (socialTabs.selectedTabPosition == 0) {
                socialAdapter.submitLeaderboard(profiles)
                renderEmptyState(
                    profiles = profiles,
                    emptyMessage = R.string.social_empty_leaderboard
                )
            }
        }

        viewModel.friends.observe(viewLifecycleOwner) { profiles ->
            if (socialTabs.selectedTabPosition == 1) {
                renderFriendsTab()
            }
        }

        viewModel.incomingFriendRequests.observe(viewLifecycleOwner) {
            if (socialTabs.selectedTabPosition == 1) {
                renderFriendsTab()
            }
        }

        viewModel.addFriendResult.observe(viewLifecycleOwner) { result ->
            result?.let {
                val message = it.message.asString(requireContext())
                showSnackbar(message, isError = it.isError)
                viewModel.clearAddFriendResult()
            }
        }
    }

    private fun renderSocialState(state: SocialViewModel.SocialUiState) {
        when (state) {
            SocialViewModel.SocialUiState.Loading -> {
                loadingIndicator.visibility = View.VISIBLE
                emptyStateText.visibility = View.GONE
                socialRecycler.visibility = View.VISIBLE
            }

            is SocialViewModel.SocialUiState.Loaded -> {
                loadingIndicator.visibility = View.GONE
                if (socialTabs.selectedTabPosition == 0) {
                    socialAdapter.submitLeaderboard(state.leaderboard)
                    renderEmptyState(state.leaderboard, R.string.social_empty_leaderboard)
                } else {
                    socialAdapter.submitFriends(
                        friends = state.friends,
                        incomingRequests = state.incomingRequests
                    )
                    renderEmptyState(state.friends + state.incomingRequests, R.string.social_empty_friends)
                }
            }

            SocialViewModel.SocialUiState.Empty -> {
                loadingIndicator.visibility = View.GONE
                renderEmptyState(
                    profiles = emptyList(),
                    emptyMessage = selectedEmptyMessage()
                )
            }

            is SocialViewModel.SocialUiState.Error -> {
                loadingIndicator.visibility = View.GONE
                showSnackbar(state.message.asString(requireContext()), isError = true)
                renderEmptyState(
                    profiles = emptyList(),
                    emptyMessage = selectedEmptyMessage()
                )
            }
        }
    }

    private fun showRemoveFriendDialog(profile: Profile) {
        AlertDialog.Builder(requireContext(), R.style.Wanderly_AlertDialog)
            .setTitle(R.string.social_remove_friend_title)
            .setMessage(getString(R.string.social_remove_friend_message, profile.username ?: getString(R.string.profile_default_name)))
            .setPositiveButton(R.string.social_remove_friend_confirm) { _, _ ->
                viewModel.removeFriend(profile.id)
            }
            .setNegativeButton(R.string.social_remove_friend_cancel, null)
            .show()
    }

    private fun renderEmptyState(profiles: List<Profile>, emptyMessage: Int) {
        if (profiles.isEmpty()) {
            socialRecycler.visibility = View.GONE
            emptyStateText.visibility = View.VISIBLE
            emptyStateText.text = getString(emptyMessage)
        } else {
            socialRecycler.visibility = View.VISIBLE
            emptyStateText.visibility = View.GONE
        }
    }

    private fun renderFriendsTab() {
        val acceptedFriends = viewModel.friends.value.orEmpty()
        val incomingRequests = viewModel.incomingFriendRequests.value.orEmpty()
        socialAdapter.submitFriends(
            friends = acceptedFriends,
            incomingRequests = incomingRequests
        )
        renderEmptyState(
            profiles = incomingRequests + acceptedFriends,
            emptyMessage = R.string.social_empty_friends
        )
    }

    private fun selectedEmptyMessage(): Int {
        return if (socialTabs.selectedTabPosition == 1) {
            R.string.social_empty_friends
        } else {
            R.string.social_empty_leaderboard
        }
    }

    private suspend fun applyPendingInviteCode() {
        val pendingCode = repository.consumePendingInviteCode() ?: return
        friendCodeInput.setText(pendingCode)
        friendCodeInput.setSelection(pendingCode.length)
        viewModel.addFriend(pendingCode)
    }
}

class SocialAdapter(
    private val onRemoveClick: (Profile) -> Unit,
    private val onAcceptClick: (Profile) -> Unit,
    private val onRejectClick: (Profile) -> Unit
) : ListAdapter<SocialAdapter.SocialRow, SocialAdapter.ViewHolder>(SocialRowDiffCallback()) {

    sealed class SocialRow(open val profile: Profile) {
        data class Leaderboard(override val profile: Profile, val rank: Int) : SocialRow(profile)
        data class Friend(override val profile: Profile) : SocialRow(profile)
        data class IncomingRequest(override val profile: Profile) : SocialRow(profile)
    }

    fun submitLeaderboard(list: List<Profile>) {
        submitList(list.mapIndexed { index, profile -> SocialRow.Leaderboard(profile, index + 1) })
    }

    fun submitFriends(friends: List<Profile>, incomingRequests: List<Profile>) {
        submitList(
            incomingRequests.map { SocialRow.IncomingRequest(it) } +
                friends.map { SocialRow.Friend(it) }
        )
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rankNumber: TextView = view.findViewById(R.id.rank_number)
        val username: TextView = view.findViewById(R.id.username)
        val rankName: TextView = view.findViewById(R.id.rank_name)
        val honeyAmount: TextView = view.findViewById(R.id.honey_amount)
        val avatarInitial: TextView = view.findViewById(R.id.avatar_initial)
        val avatarImage: ImageView = view.findViewById(R.id.avatar_image)
        val removeBtn: ImageView = view.findViewById(R.id.remove_friend_btn)
        val acceptBtn: ImageView = view.findViewById(R.id.accept_friend_request_btn)
        val rejectBtn: ImageView = view.findViewById(R.id.reject_friend_request_btn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_social_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val profile = row.profile

        if (row is SocialRow.Leaderboard) {
            holder.rankNumber.visibility = View.VISIBLE
            holder.rankNumber.text = holder.itemView.context.getString(R.string.rank_number_format, row.rank)
        } else {
            holder.rankNumber.visibility = View.GONE
            holder.rankNumber.text = ""
        }

        holder.username.text = profile.username ?: holder.itemView.context.getString(R.string.unknown_explorer)
        holder.honeyAmount.text = String.format(Locale.getDefault(), "%d", profile.honey ?: 0)
        
        val rank = HiveRank.fromHoney(profile.honey)
        holder.rankName.text = if (row is SocialRow.IncomingRequest) {
            holder.itemView.context.getString(R.string.social_friend_request_label)
        } else {
            holder.itemView.context.getString(RankUiFormatter.rankNameRes(rank))
        }

        holder.removeBtn.visibility = if (row is SocialRow.Friend) View.VISIBLE else View.GONE
        holder.removeBtn.setOnClickListener(if (row is SocialRow.Friend) View.OnClickListener { onRemoveClick(profile) } else null)
        holder.acceptBtn.visibility = if (row is SocialRow.IncomingRequest) View.VISIBLE else View.GONE
        holder.acceptBtn.setOnClickListener(if (row is SocialRow.IncomingRequest) View.OnClickListener { onAcceptClick(profile) } else null)
        holder.rejectBtn.visibility = if (row is SocialRow.IncomingRequest) View.VISIBLE else View.GONE
        holder.rejectBtn.setOnClickListener(if (row is SocialRow.IncomingRequest) View.OnClickListener { onRejectClick(profile) } else null)

        AvatarLoader.loadAvatar(
            holder.avatarImage,
            holder.avatarInitial,
            profile.avatar_url,
            profile.username ?: holder.itemView.context.getString(R.string.profile_default_name)
        )
    }

    private class SocialRowDiffCallback : DiffUtil.ItemCallback<SocialRow>() {
        override fun areItemsTheSame(oldItem: SocialRow, newItem: SocialRow): Boolean =
            oldItem::class == newItem::class && oldItem.profile.id == newItem.profile.id

        override fun areContentsTheSame(oldItem: SocialRow, newItem: SocialRow): Boolean = oldItem == newItem
    }
}
