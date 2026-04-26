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
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.ui.common.AvatarLoader
import com.novahorizon.wanderly.ui.common.WanderlyViewModelFactory
import com.novahorizon.wanderly.ui.common.showSnackbar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.Locale

class SocialFragment : Fragment() {

    private val viewModel: SocialViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyGraph.repository(requireContext()))
    }
    private val repository by lazy(LazyThreadSafetyMode.NONE) {
        WanderlyGraph.repository(requireContext())
    }
    
    private lateinit var socialTabs: TabLayout
    private lateinit var socialRecycler: RecyclerView
    private lateinit var addFriendLayout: View
    private lateinit var friendCodeInput: EditText
    private lateinit var addFriendButton: View
    private lateinit var loadingIndicator: View
    private lateinit var emptyStateText: TextView
    private var formattingFriendCode = false

    private val socialAdapter = SocialAdapter { profile ->
        showRemoveFriendDialog(profile)
    }

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
                socialAdapter.submitProfiles(profiles, showRank = true, canRemove = false)
                renderEmptyState(
                    profiles = profiles,
                    emptyMessage = R.string.social_empty_leaderboard
                )
            }
        }

        viewModel.friends.observe(viewLifecycleOwner) { profiles ->
            if (socialTabs.selectedTabPosition == 1) {
                socialAdapter.submitProfiles(profiles, showRank = false, canRemove = true)
                renderEmptyState(
                    profiles = profiles,
                    emptyMessage = R.string.social_empty_friends
                )
            }
        }

        viewModel.addFriendResult.observe(viewLifecycleOwner) { result ->
            result?.let {
                val isError = !it.contains("successfully")
                showSnackbar(it, isError = isError)
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
                    socialAdapter.submitProfiles(state.leaderboard, showRank = true, canRemove = false)
                    renderEmptyState(state.leaderboard, R.string.social_empty_leaderboard)
                } else {
                    socialAdapter.submitProfiles(state.friends, showRank = false, canRemove = true)
                    renderEmptyState(state.friends, R.string.social_empty_friends)
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
                showSnackbar(getString(state.messageRes), isError = true)
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

class SocialAdapter(private val onRemoveClick: (Profile) -> Unit) : ListAdapter<Profile, SocialAdapter.ViewHolder>(ProfileDiffCallback()) {
    private var showRank = true
    private var canRemove = false

    fun submitProfiles(list: List<Profile>, showRank: Boolean, canRemove: Boolean) {
        val displayModeChanged = this.showRank != showRank || this.canRemove != canRemove
        this.showRank = showRank
        this.canRemove = canRemove
        submitList(list.toList())
        if (displayModeChanged && itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rankNumber: TextView = view.findViewById(R.id.rank_number)
        val username: TextView = view.findViewById(R.id.username)
        val rankName: TextView = view.findViewById(R.id.rank_name)
        val honeyAmount: TextView = view.findViewById(R.id.honey_amount)
        val avatarInitial: TextView = view.findViewById(R.id.avatar_initial)
        val avatarImage: ImageView = view.findViewById(R.id.avatar_image)
        val removeBtn: ImageView = view.findViewById(R.id.remove_friend_btn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_social_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = getItem(position)

        holder.rankNumber.visibility = if (showRank) View.VISIBLE else View.GONE
        holder.rankNumber.text = holder.itemView.context.getString(R.string.rank_number_format, position + 1)

        holder.username.text = profile.username ?: holder.itemView.context.getString(R.string.unknown_explorer)
        holder.honeyAmount.text = (profile.honey ?: 0).toString()
        
        val rank = HiveRank.fromHoney(profile.honey)
        holder.rankName.text = getRankName(holder.itemView.context, rank)

        if (canRemove) {
            holder.removeBtn.visibility = View.VISIBLE
            holder.removeBtn.setOnClickListener { onRemoveClick(profile) }
        } else {
            holder.removeBtn.visibility = View.GONE
            holder.removeBtn.setOnClickListener(null)
        }

        AvatarLoader.loadAvatar(
            holder.avatarImage,
            holder.avatarInitial,
            profile.avatar_url,
            profile.username ?: holder.itemView.context.getString(R.string.profile_default_name)
        )
    }

    private fun getRankName(context: android.content.Context, rank: Int) = when(rank) {
        1 -> context.getString(R.string.rank_1)
        2 -> context.getString(R.string.rank_2)
        3 -> context.getString(R.string.rank_3)
        else -> context.getString(R.string.rank_4)
    }

    private class ProfileDiffCallback : DiffUtil.ItemCallback<Profile>() {
        override fun areItemsTheSame(oldItem: Profile, newItem: Profile): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Profile, newItem: Profile): Boolean = oldItem == newItem
    }
}
