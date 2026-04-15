package com.novahorizon.wanderly.ui.profile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.novahorizon.wanderly.databinding.DialogClassSelectionBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.novahorizon.wanderly.AuthActivity
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.databinding.FragmentProfileBinding
import com.novahorizon.wanderly.databinding.DialogEditUsernameBinding
import com.novahorizon.wanderly.showSnackbar
import com.yalantis.ucrop.UCrop
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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
                    val prefs = requireActivity().getSharedPreferences("WanderlyPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("remember_me", false).apply()
                    requireActivity().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
                    startActivity(Intent(requireContext(), AuthActivity::class.java))
                    requireActivity().finish()
                } catch (e: Exception) {
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
                val base64String = withContext(Dispatchers.IO) {
                    val inputStream = requireContext().contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                    val bytes = outputStream.toByteArray()
                    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }

                val profile = currentProfile ?: return@launch
                val updatedProfile = profile.copy(avatar_url = base64String)
                repository.updateProfile(updatedProfile)
                
                currentProfile = updatedProfile
                updateAvatarDisplay(base64String, updatedProfile.username ?: "")
                showSnackbar("Avatar updated! 🐝", isError = false)
            } catch (e: Exception) {
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
                showSnackbar("Username updated! 🐝", isError = false)
            } catch (e: Exception) {
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

        if (!newBadges.contains("Early Bee")) {
            newBadges.add("Early Bee")
        }
        if (currentHoney >= 100 && !newBadges.contains("Scout Bee")) {
            newBadges.add("Scout Bee")
        }
        if (currentHoney >= 300 && !newBadges.contains("Expert Bee")) {
            newBadges.add("Expert Bee")
        }
        if (currentHoney >= 600 && !newBadges.contains("Queen Explorer")) {
            newBadges.add("Queen Explorer")
        }
        if (currentHoney >= 1000 && !newBadges.contains("Honey Hoarder")) {
            newBadges.add("Honey Hoarder")
        }
        if (currentStreak >= 7 && !newBadges.contains("Streak Master")) {
            newBadges.add("Streak Master")
        }

        if (newBadges.size > currentBadges.size) {
            val updatedProfile = profile.copy(badges = newBadges.toList())
            lifecycleScope.launch {
                repository.updateProfile(updatedProfile)
                // updateUI will be called via the repository flow collection
            }
        } else {
            currentProfile = profile
            updateUI(profile)
        }
    }

    private fun updateUI(profile: Profile) {
        binding.username.text = profile.username ?: "Explorer"
        
        if (!profile.explorer_class.isNullOrEmpty()) {
            binding.explorerClassText.text = profile.explorer_class
            binding.explorerClassText.visibility = View.VISIBLE
        } else {
            binding.explorerClassText.visibility = View.GONE
        }

        if (!profile.friend_code.isNullOrEmpty()) {
            binding.friendCodeDisplay.text = "Friend Code: ${profile.friend_code}"
            binding.friendCodeDisplay.visibility = View.VISIBLE
        } else {
            binding.friendCodeDisplay.visibility = View.GONE
        }

        val currentHoney = profile.honey ?: 0
        val currentStreak = profile.streak_count ?: 0
        val flights = currentHoney / 50

        // Class selection trigger: 10 flights (500 honey)
        if (flights >= 10 && profile.explorer_class.isNullOrEmpty() && !isClassDialogShowing) {
            showClassSelectionDialog(profile)
        }
        
        val currentRank = when {
            currentHoney >= 600 -> 4
            currentHoney >= 300 -> 3
            currentHoney >= 100 -> 2
            else -> 1
        }

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

        val nextRankHoney = getMinHoneyForRank(currentRank + 1)
        val currentRankMinHoney = getMinHoneyForRank(currentRank)
        val diff = nextRankHoney - currentRankMinHoney
        
        val progress = if (diff > 0) {
            ((currentHoney - currentRankMinHoney).toFloat() / diff * 100).toInt()
        } else 100
        
        binding.hiveProgress.progress = progress.coerceIn(0, 100)
        
        val needed = nextRankHoney - currentHoney
        binding.progressText.text = if (currentRank < 4) {
            if (needed > 0) "Next Rank in $needed Honey" else "Rank Up Ready! 🐝"
        } else "Maximum Rank Achieved! 👑"
        
        updateAvatarDisplay(profile.avatar_url, profile.username ?: "")
        
        if (profile.badges.isNullOrEmpty()) {
            binding.badgesRecycler.visibility = View.GONE
            binding.badgesTitle.text = "No honeycombs collected yet! 🐝"
        } else {
            binding.badgesRecycler.visibility = View.VISIBLE
            binding.badgesTitle.text = "My Honeycombs (Badges)"
            binding.badgesRecycler.layoutManager = GridLayoutManager(requireContext(), 3)
            binding.badgesRecycler.adapter = BadgesAdapter(profile.badges!!)
        }
    }

    private fun updateAvatarDisplay(avatarData: String?, username: String) {
        if (!avatarData.isNullOrEmpty()) {
            binding.avatarInitial.visibility = View.GONE
            binding.buzzyAvatar.visibility = View.VISIBLE
            
            val imageBytes = try {
                if (avatarData.startsWith("data:image")) {
                    Base64.decode(avatarData.substringAfter("base64,"), Base64.DEFAULT)
                } else {
                    Base64.decode(avatarData, Base64.DEFAULT)
                }
            } catch (e: Exception) { null }
            
            if (imageBytes != null) {
                Glide.with(this)
                    .load(imageBytes)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .into(binding.buzzyAvatar)
            }
        } else {
            binding.buzzyAvatar.visibility = View.GONE
            binding.avatarInitial.visibility = View.VISIBLE
            binding.avatarInitial.text = username.firstOrNull()?.uppercase() ?: "E"
        }
    }

    private fun showEditUsernameDialog() {
        val dialogBinding = DialogEditUsernameBinding.inflate(layoutInflater)
        dialogBinding.usernameEditText.setText(currentProfile?.username)
        AlertDialog.Builder(requireContext(), R.style.Wanderly_AlertDialog)
            .setView(dialogBinding.root)
            .setPositiveButton("Update") { _, _ ->
                val newUsername = dialogBinding.usernameEditText.text.toString().trim()
                if (newUsername.length >= 3) updateUsername(newUsername)
                else showSnackbar("Username too short", isError = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getRankName(rank: Int) = when(rank) {
        1 -> getString(R.string.rank_1)
        2 -> getString(R.string.rank_2)
        3 -> getString(R.string.rank_3)
        else -> getString(R.string.rank_4)
    }

    private fun getMinHoneyForRank(rank: Int) = when(rank) {
        1 -> 0
        2 -> 100
        3 -> 300
        4 -> 600
        else -> 1000
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
                        showSnackbar("Class Locked: $className ⚔️", isError = false)
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
