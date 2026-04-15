package com.novahorizon.wanderly.ui

import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.tabs.TabLayout
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.showSnackbar

class SocialFragment : Fragment() {

    private val viewModel: SocialViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyRepository(requireContext()))
    }
    
    private lateinit var socialTabs: TabLayout
    private lateinit var socialRecycler: RecyclerView
    private lateinit var addFriendLayout: View
    private lateinit var friendUsernameInput: TextView
    private lateinit var addFriendButton: View
    private lateinit var loadingIndicator: View

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
        friendUsernameInput = view.findViewById(R.id.friend_username_input)
        addFriendButton = view.findViewById(R.id.add_friend_button)
        loadingIndicator = view.findViewById(R.id.social_loading)
        
        socialRecycler.layoutManager = LinearLayoutManager(requireContext())
        socialRecycler.adapter = socialAdapter

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
                    }
                    1 -> { // Friends
                        addFriendLayout.visibility = View.VISIBLE
                        viewModel.loadFriends()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        addFriendButton.setOnClickListener {
            val username = friendUsernameInput.text.toString().trim()
            if (username.isNotEmpty()) {
                viewModel.addFriend(username)
                friendUsernameInput.text = ""
            }
        }

        // Load default tab
        viewModel.loadLeaderboard()
    }

    private fun setupObservers() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.leaderboard.observe(viewLifecycleOwner) { profiles ->
            if (socialTabs.selectedTabPosition == 0) {
                socialAdapter.submitList(profiles, showRank = true, canRemove = false)
            }
        }

        viewModel.friends.observe(viewLifecycleOwner) { profiles ->
            if (socialTabs.selectedTabPosition == 1) {
                socialAdapter.submitList(profiles, showRank = false, canRemove = true)
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

    private fun showRemoveFriendDialog(profile: Profile) {
        AlertDialog.Builder(requireContext(), R.style.Wanderly_AlertDialog)
            .setTitle("Remove Friend")
            .setMessage("Are you sure you want to remove ${profile.username} from your hive?")
            .setPositiveButton("Remove") { _, _ ->
                viewModel.removeFriend(profile.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class SocialAdapter(private val onRemoveClick: (Profile) -> Unit) : RecyclerView.Adapter<SocialAdapter.ViewHolder>() {

    private var profiles = listOf<Profile>()
    private var showRank = true
    private var canRemove = false

    fun submitList(list: List<Profile>, showRank: Boolean, canRemove: Boolean) {
        this.profiles = list
        this.showRank = showRank
        this.canRemove = canRemove
        notifyDataSetChanged()
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
        val profile = profiles[position]

        holder.rankNumber.visibility = if (showRank) View.VISIBLE else View.GONE
        holder.rankNumber.text = "#${position + 1}"

        holder.username.text = profile.username ?: "Unknown"
        holder.honeyAmount.text = (profile.honey ?: 0).toString()
        
        val currentHoney = profile.honey ?: 0
        val rank = when {
            currentHoney >= 600 -> 4
            currentHoney >= 300 -> 3
            currentHoney >= 100 -> 2
            else -> 1
        }
        holder.rankName.text = getRankName(rank)

        if (canRemove) {
            holder.removeBtn.visibility = View.VISIBLE
            holder.removeBtn.setOnClickListener { onRemoveClick(profile) }
        } else {
            holder.removeBtn.visibility = View.GONE
        }

        if (!profile.avatar_url.isNullOrEmpty()) {
            holder.avatarInitial.visibility = View.GONE
            holder.avatarImage.visibility = View.VISIBLE
            val imageBytes = try {
                if (profile.avatar_url.startsWith("data:image")) {
                    Base64.decode(profile.avatar_url.substringAfter("base64,"), Base64.DEFAULT)
                } else {
                    Base64.decode(profile.avatar_url, Base64.DEFAULT)
                }
            } catch (e: Exception) { null }

            if (imageBytes != null) {
                Glide.with(holder.itemView.context)
                    .load(imageBytes)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .into(holder.avatarImage)
            }
        } else {
            holder.avatarImage.visibility = View.GONE
            holder.avatarInitial.visibility = View.VISIBLE
            holder.avatarInitial.text = profile.username?.firstOrNull()?.uppercase() ?: "E"
        }
    }

    override fun getItemCount() = profiles.size

    private fun getRankName(rank: Int) = when(rank) {
        1 -> "Worker Bee"
        2 -> "Scout Bee"
        3 -> "Expert Bee"
        else -> "Queen of Exploration"
    }
}
