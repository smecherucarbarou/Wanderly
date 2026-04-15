package com.novahorizon.wanderly.ui.profile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novahorizon.wanderly.AuthActivity
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.databinding.DialogClassSelectionBinding
import com.novahorizon.wanderly.databinding.DialogEditUsernameBinding
import com.novahorizon.wanderly.databinding.FragmentProfileBinding
import com.novahorizon.wanderly.showSnackbar
import com.novahorizon.wanderly.ui.AvatarLoader
import com.yalantis.ucrop.UCrop
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import java.io.File

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private var currentProfile: Profile? = null
    private lateinit var repository: WanderlyRepository
    private var isClassDialogShowing = false

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                uploadAvatarToSupabase(resultUri)
            }
        }
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val destinationUri = Uri.fromFile(File(requireContext().cacheDir, "temp_avatar.jpg"))
            val uCropIntent = UCrop.of(uri, destinationUri)
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(400, 400)
                .withOptions(UCrop.Options().apply {
                    setCircleDimmedLayer(true)
                    setShowCropGrid(false)
                })
                .getIntent(requireContext())
            cropImage.launch(uCropIntent)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        repository = WanderlyRepository(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.currentProfile.collect { profile ->
                    profile?.let {
                        currentProfile = it
                        updateUI(it)
                    }
                }
            }
        }

        binding.avatarContainer.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.editUsernameButton.setOnClickListener { showEditUsernameDialog() }
        binding.logoutButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    SupabaseClient.client.auth.signOut()
                    val prefs = requireActivity().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putBoolean(Constants.KEY_REMEMBER_ME, false).apply()
                    prefs.edit().clear().apply()
                    startActivity(Intent(requireContext(), AuthActivity::class.java))
                    requireActivity().finish()
                } catch (_: Exception) {
                    showSnackbar("Logout failed", isError = true)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadProfile()
    }

    private fun uploadAvatarToSupabase(uri: Uri) {
        lifecycleScope.launch {
            try {
                val profile = currentProfile ?: return@launch
                val avatarUrl = repository.uploadAvatar(uri, profile.id)
                    ?: throw IllegalStateException("Avatar upload failed")

                val updatedProfile = profile.copy(avatar_url = avatarUrl)
                repository.updateProfile(updatedProfile)

                currentProfile = updatedProfile
                updateAvatarDisplay(avatarUrl, updatedProfile.username ?: "")
                showSnackbar("Avatar updated!", isError = false)
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Avatar upload failed", e)
                showSnackbar("Failed to upload avatar", isError = true)
            }
        }
    }

    private fun updateUsername(newUsername: String) {
        lifecycleScope.launch {
            try {
                val profile = currentProfile ?: return@launch
                val updatedProfile = profile.copy(username = newUsername)
                repository.updateProfile(updatedProfile)

                currentProfile = updatedProfile
                binding.username.text = newUsername
                showSnackbar("Username updated!", isError = false)
            } catch (_: Exception) {
                showSnackbar("Failed to update username", isError = true)
            }
        }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            try {
                val result = repository.getCurrentProfile()
                if (result != null) {
                    currentProfile = result
                    checkAndUnlockBadges(result)
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error loading profile", e)
                showSnackbar("Could not load profile from hive.", isError = true)
            }
        }
    }

    private fun checkAndUnlockBadges(profile: Profile) {
        val currentHoney = profile.honey ?: 0
        val currentStreak = profile.streak_count ?: 0
        val currentBadges = profile.badges?.toSet() ?: emptySet()
        val newBadges = currentBadges.toMutableSet()

        if ("Early Bee" !in newBadges) newBadges.add("Early Bee")
        if (currentHoney >= 100 && "Scout Bee" !in newBadges) newBadges.add("Scout Bee")
        if (currentHoney >= 300 && "Expert Bee" !in newBadges) newBadges.add("Expert Bee")
        if (currentHoney >= 600 && "Queen Explorer" !in newBadges) newBadges.add("Queen Explorer")
        if (currentHoney >= 1000 && "Honey Hoarder" !in newBadges) newBadges.add("Honey Hoarder")
        if (currentStreak >= 7 && "Streak Master" !in newBadges) newBadges.add("Streak Master")

        if (newBadges.size > currentBadges.size) {
            val updatedProfile = profile.copy(badges = newBadges.toList())
            lifecycleScope.launch {
                repository.updateProfile(updatedProfile)
            }
        } else {
            currentProfile = profile
            updateUI(profile)
        }
    }

    private fun updateUI(profile: Profile) {
        binding.username.text = profile.username ?: getString(R.string.profile_default_name)

        if (!profile.explorer_class.isNullOrEmpty()) {
            binding.explorerClassText.text = profile.explorer_class
            binding.explorerClassText.visibility = View.VISIBLE
        } else {
            binding.explorerClassText.visibility = View.GONE
        }

        if (!profile.friend_code.isNullOrEmpty()) {
            binding.friendCodeDisplay.text = getString(R.string.friend_code_display, profile.friend_code)
            binding.friendCodeDisplay.visibility = View.VISIBLE
            binding.friendCodeDisplay.setOnClickListener { copyFriendCode(profile.friend_code) }
        } else {
            binding.friendCodeDisplay.visibility = View.GONE
            binding.friendCodeDisplay.setOnClickListener(null)
        }

        val currentHoney = profile.honey ?: 0
        val currentStreak = profile.streak_count ?: 0
        val flights = currentHoney / 50

        if (flights >= 10 && profile.explorer_class.isNullOrEmpty() && !isClassDialogShowing) {
            showClassSelectionDialog(profile)
        }

        val currentRank = HiveRank.fromHoney(currentHoney)
        binding.honeyTotal.text = currentHoney.toString()
        binding.streakCount.text = currentStreak.toString()
        binding.rankBadge.text = getRankName(currentRank)
        binding.missionsCompletedCount.text = flights.toString()

        if (currentStreak >= 3) {
            binding.streakAura.visibility = View.VISIBLE
            val fireDrawable = when {
                currentStreak >= 50 -> R.drawable.ic_streak_fire_50
                currentStreak >= 25 -> R.drawable.ic_streak_fire_25
                currentStreak >= 5 -> R.drawable.ic_streak_fire_5
                else -> R.drawable.ic_streak_fire
            }
            binding.streakAura.setImageResource(fireDrawable)
        } else {
            binding.streakAura.visibility = View.GONE
        }

        val nextRankHoney = HiveRank.minHoneyForRank(currentRank + 1)
        val currentRankMinHoney = HiveRank.minHoneyForRank(currentRank)
        val diff = nextRankHoney - currentRankMinHoney
        val progress = if (diff > 0) {
            ((currentHoney - currentRankMinHoney).toFloat() / diff * 100).toInt()
        } else {
            100
        }

        binding.hiveProgress.progress = progress.coerceIn(0, 100)

        val needed = nextRankHoney - currentHoney
        binding.progressText.text = if (currentRank < 4) {
            if (needed > 0) {
                getString(R.string.profile_next_rank_in_honey, needed)
            } else {
                getString(R.string.profile_rank_up_ready)
            }
        } else {
            getString(R.string.profile_max_rank)
        }

        updateAvatarDisplay(profile.avatar_url, profile.username ?: getString(R.string.profile_default_name))

        if (profile.badges.isNullOrEmpty()) {
            binding.badgesRecycler.visibility = View.GONE
            binding.badgesTitle.text = getString(R.string.profile_badges_empty)
        } else {
            binding.badgesRecycler.visibility = View.VISIBLE
            binding.badgesTitle.text = getString(R.string.profile_badges_title)
            binding.badgesRecycler.layoutManager = GridLayoutManager(requireContext(), 3)
            binding.badgesRecycler.adapter = BadgesAdapter(profile.badges!!)
        }
    }

    private fun copyFriendCode(friendCode: String) {
        val clipboardManager = requireContext().getSystemService(ClipboardManager::class.java)
        clipboardManager?.setPrimaryClip(ClipData.newPlainText("Friend Code", friendCode))
        showSnackbar(getString(R.string.friend_code_copied), isError = false)
    }

    private fun updateAvatarDisplay(avatarData: String?, username: String) {
        AvatarLoader.loadAvatar(binding.buzzyAvatar, binding.avatarInitial, avatarData, username)
    }

    private fun showEditUsernameDialog() {
        val dialogBinding = DialogEditUsernameBinding.inflate(layoutInflater)
        dialogBinding.usernameEditText.setText(currentProfile?.username)
        AlertDialog.Builder(requireContext(), R.style.Wanderly_AlertDialog)
            .setView(dialogBinding.root)
            .setPositiveButton("Update") { _, _ ->
                val newUsername = dialogBinding.usernameEditText.text.toString().trim()
                if (newUsername.length >= 3) {
                    updateUsername(newUsername)
                } else {
                    showSnackbar("Username too short", isError = true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getRankName(rank: Int) = when (rank) {
        1 -> getString(R.string.rank_1)
        2 -> getString(R.string.rank_2)
        3 -> getString(R.string.rank_3)
        else -> getString(R.string.rank_4)
    }

    private fun showClassSelectionDialog(profile: Profile) {
        isClassDialogShowing = true
        val dialogBinding = DialogClassSelectionBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext(), R.style.Wanderly_AlertDialog)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .setOnDismissListener { isClassDialogShowing = false }
            .create()

        dialog.window?.setWindowAnimations(R.style.Wanderly_DialogAnimation)

        dialogBinding.classExplorer.setOnClickListener { confirmClassSelection(profile, "EXPLORER", dialog) }
        dialogBinding.classSocial.setOnClickListener { confirmClassSelection(profile, "SOCIAL BEE", dialog) }
        dialogBinding.classAdventurer.setOnClickListener { confirmClassSelection(profile, "ADVENTURER", dialog) }

        dialog.show()
    }

    private fun confirmClassSelection(profile: Profile, className: String, parentDialog: AlertDialog) {
        AlertDialog.Builder(requireContext(), R.style.Wanderly_AlertDialog)
            .setTitle("ARE YOU SURE?")
            .setMessage("The path of the $className is permanent.")
            .setPositiveButton("I AM READY") { _, _ ->
                lifecycleScope.launch {
                    val updated = profile.copy(explorer_class = className)
                    val success = repository.updateProfile(updated)
                    if (success) {
                        currentProfile = updated
                        updateUI(updated)
                        parentDialog.dismiss()
                        showSnackbar("Class locked: $className", isError = false)
                    } else {
                        showSnackbar("Failed to lock class. Check your Hive connection.", isError = true)
                    }
                }
            }
            .setNegativeButton("WAIT", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class BadgesAdapter(private val badges: List<String>) : RecyclerView.Adapter<BadgesAdapter.BadgeViewHolder>() {
    class BadgeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.badge_name)
        val iconImage: ImageView = view.findViewById(R.id.badge_icon)
        val background: View = view.findViewById(R.id.badge_background)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BadgeViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_badge, parent, false)
        return BadgeViewHolder(view)
    }

    override fun onBindViewHolder(holder: BadgeViewHolder, position: Int) {
        val badge = badges[position]
        holder.nameText.text = badge
        when (badge) {
            "Early Bee" -> {
                holder.iconImage.setImageResource(R.drawable.ic_buzzy)
                holder.background.setBackgroundResource(R.drawable.bg_badge_early)
            }

            "Scout Bee" -> {
                holder.iconImage.setImageResource(R.drawable.ic_honeycomb)
                holder.background.setBackgroundResource(R.drawable.bg_badge_scout)
            }

            "Expert Bee" -> {
                holder.iconImage.setImageResource(R.drawable.ic_streak_fire)
                holder.background.setBackgroundResource(R.drawable.bg_badge_expert)
            }

            "Queen Explorer" -> {
                holder.iconImage.setImageResource(R.drawable.ic_streak_fire_50)
                holder.background.setBackgroundResource(R.drawable.bg_badge_queen)
            }

            "Honey Hoarder" -> {
                holder.iconImage.setImageResource(R.drawable.ic_honeycomb)
                holder.background.setBackgroundResource(R.drawable.bg_badge_expert)
            }

            "Streak Master" -> {
                holder.iconImage.setImageResource(R.drawable.ic_streak_fire_25)
                holder.background.setBackgroundResource(R.drawable.bg_badge_queen)
            }

            else -> {
                holder.iconImage.setImageResource(R.drawable.ic_honeycomb)
                holder.background.setBackgroundResource(R.drawable.bg_badge_circle)
            }
        }
    }

    override fun getItemCount(): Int = badges.size
}
