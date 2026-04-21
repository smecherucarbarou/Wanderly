package com.novahorizon.wanderly.ui.profile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.auth.SessionNavigator
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileRepository
import com.novahorizon.wanderly.databinding.FragmentProfileBinding
import com.novahorizon.wanderly.services.HiveRealtimeService
import com.novahorizon.wanderly.ui.common.AvatarLoader
import com.novahorizon.wanderly.ui.common.WanderlyViewModelFactory
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.yalantis.ucrop.UCrop
import java.io.File
import java.text.NumberFormat

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private var currentProfile: Profile? = null
    private var pendingAvatarDestinationUri: Uri? = null
    private var pendingAvatarPreviewSource: String? = null
    private var pendingAvatarRemotePath: String? = null
    private var pendingClassSelectionDialog: androidx.appcompat.app.AlertDialog? = null
    private var isClassDialogShowing = false
    private val badgesAdapter = BadgesAdapter()
    private val viewModel: ProfileViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyGraph.repository(requireContext()))
    }

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

        val resultUri = result.data?.let(UCrop::getOutput)
        if (resultUri == null) {
            Toast.makeText(requireContext(), R.string.profile_avatar_crop_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (!isUsableCropResult(resultUri)) {
            Log.e("ProfileFragment", "Avatar crop result was empty or unreadable: $resultUri")
            Toast.makeText(requireContext(), R.string.profile_avatar_crop_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        uploadAvatarToSupabase(resultUri)
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val destinationFile = File.createTempFile("avatar_crop_", ".jpg", requireContext().cacheDir)
            pendingAvatarDestinationUri = Uri.fromFile(destinationFile)
            val destinationUri = pendingAvatarDestinationUri ?: return@registerForActivityResult
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
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            profile?.let {
                currentProfile = it
                updateUI(it)
            }
        }

        viewModel.profileEvent.observe(viewLifecycleOwner) { event ->
            when (event) {
                is ProfileViewModel.ProfileEvent.ShowMessage -> {
                    if (event.isError && pendingAvatarPreviewSource != null && pendingAvatarRemotePath == null) {
                        pendingAvatarPreviewSource = null
                        pendingAvatarRemotePath = null
                    }
                    showSnackbar(event.message, isError = event.isError)
                    viewModel.clearProfileEvent()
                }

                is ProfileViewModel.ProfileEvent.AvatarUpdated -> {
                    pendingAvatarRemotePath = event.remotePath
                    showSnackbar(getString(R.string.profile_avatar_updated), isError = false)
                    viewModel.clearProfileEvent()
                }

                is ProfileViewModel.ProfileEvent.ClassLocked -> {
                    pendingClassSelectionDialog?.dismiss()
                    pendingClassSelectionDialog = null
                    currentProfile?.copy(explorer_class = event.className)?.let {
                        currentProfile = it
                        updateUI(it)
                    }
                    showSnackbar(
                        getString(R.string.profile_class_locked, event.className),
                        isError = false
                    )
                    viewModel.clearProfileEvent()
                }

                ProfileViewModel.ProfileEvent.LoggedOut -> {
                    SessionNavigator.openAuth(requireActivity())
                    viewModel.clearProfileEvent()
                }

                null -> Unit
            }
        }

        binding.avatarContainer.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.badgesRecycler.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.badgesRecycler.adapter = badgesAdapter
        binding.badgesRecycler.isNestedScrollingEnabled = false
        binding.editUsernameButton.setOnClickListener { showEditUsernameDialog() }
        binding.adminDashboardButton.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_devDashboard)
        }
        binding.logoutButton.setOnClickListener {
            requireContext().stopService(Intent(requireContext(), HiveRealtimeService::class.java))
            viewModel.logout()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadProfile()
    }

    private fun updateUI(profile: Profile) {
        val binding = _binding ?: return
        binding.username.text = profile.username ?: getString(R.string.profile_default_name)

        if (!profile.explorer_class.isNullOrEmpty()) {
            binding.explorerClassText.text = profile.explorer_class
            binding.explorerClassText.visibility = View.VISIBLE
        } else {
            binding.explorerClassText.visibility = View.GONE
        }

        if (!profile.friend_code.isNullOrEmpty()) {
            binding.friendCodeDisplay.text = getString(R.string.friend_code_display, profile.friend_code)
            binding.friendCodeLayout.visibility = View.VISIBLE
            binding.friendCodeDisplay.visibility = View.VISIBLE
            binding.friendCodeDisplay.setOnClickListener { copyFriendCode(profile.friend_code) }
            binding.shareFriendCodeButton.visibility = View.VISIBLE
            binding.shareFriendCodeButton.setOnClickListener { shareFriendCode(profile.friend_code) }
        } else {
            binding.friendCodeLayout.visibility = View.GONE
            binding.friendCodeDisplay.visibility = View.GONE
            binding.friendCodeDisplay.setOnClickListener(null)
            binding.shareFriendCodeButton.visibility = View.GONE
            binding.shareFriendCodeButton.setOnClickListener(null)
        }

        binding.adminDashboardButton.visibility = if (profile.admin_role) View.VISIBLE else View.GONE

        val currentHoney = profile.honey ?: 0
        val currentStreak = profile.streak_count ?: 0
        val flights = currentHoney / 50

        if (flights >= 10 && profile.explorer_class.isNullOrEmpty() && !isClassDialogShowing) {
            showClassSelectionDialog(profile)
        }

        val currentRank = HiveRank.fromHoney(currentHoney)
        binding.honeyTotal.text = formatWholeNumber(currentHoney)
        binding.streakCount.text = formatWholeNumber(currentStreak)
        binding.rankBadge.text = getRankName(currentRank)
        binding.missionsCompletedCount.text = formatWholeNumber(flights)

        val haloDrawable = resolveProfileHaloRes(currentStreak)
        if (haloDrawable != null) {
            binding.streakAura.visibility = View.VISIBLE
            applyProfileHalo(binding, haloDrawable)
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

        val avatarDecision = resolveAvatarPresentation(
            profileAvatarSource = profile.avatar_url,
            pendingAvatarPreviewSource = pendingAvatarPreviewSource,
            pendingAvatarRemotePath = pendingAvatarRemotePath
        )
        if (avatarDecision.shouldClearPendingPreview) {
            pendingAvatarPreviewSource = null
            pendingAvatarRemotePath = null
        }
        updateAvatarDisplay(avatarDecision.displaySource, profile.username ?: getString(R.string.profile_default_name))

        val badges = profile.badges.orEmpty()
        if (badges.isEmpty()) {
            binding.badgesRecycler.visibility = View.GONE
            binding.badgesTitle.text = getString(R.string.profile_badges_empty)
        } else {
            binding.badgesRecycler.visibility = View.VISIBLE
            binding.badgesTitle.text = getString(R.string.profile_badges_title)
            badgesAdapter.submitBadges(badges)
        }
    }

    private fun uploadAvatarToSupabase(uri: Uri) {
        val profile = currentProfile ?: return
        pendingAvatarPreviewSource = uri.toString()
        pendingAvatarRemotePath = null
        updateAvatarDisplay(
            pendingAvatarPreviewSource,
            profile.username ?: getString(R.string.profile_default_name)
        )
        viewModel.uploadAvatar(profile, uri)
    }

    private fun updateUsername(newUsername: String) {
        val profile = currentProfile ?: return
        viewModel.updateUsername(profile, newUsername)
    }

    private fun copyFriendCode(friendCode: String) {
        val clipboardManager = requireContext().getSystemService(ClipboardManager::class.java)
        clipboardManager?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.profile_friend_code_clip_label), friendCode)
        )
        showSnackbar(getString(R.string.friend_code_copied), isError = false)
    }

    private fun shareFriendCode(friendCode: String) {
        val shareMessage = getString(R.string.profile_share_invite_message, friendCode, friendCode)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareMessage)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.profile_share_friend_code)))
    }

    private fun updateAvatarDisplay(avatarData: String?, username: String) {
        val binding = _binding ?: return
        AvatarLoader.loadAvatar(binding.buzzyAvatar, binding.avatarInitial, avatarData, username)
    }

    private fun applyProfileHalo(binding: FragmentProfileBinding, haloDrawable: Int) {
        binding.streakFlameTop.setImageResource(haloDrawable)
        binding.streakFlameLeft.setImageResource(haloDrawable)
        binding.streakFlameRight.setImageResource(haloDrawable)
        binding.streakFlameBottom.setImageResource(haloDrawable)
        binding.streakFlameTopStart.setImageResource(haloDrawable)
        binding.streakFlameTopEnd.setImageResource(haloDrawable)
        binding.streakFlameBottomStart.setImageResource(haloDrawable)
        binding.streakFlameBottomEnd.setImageResource(haloDrawable)
    }

    private fun isUsableCropResult(uri: Uri): Boolean {
        val localFilePath = ProfileRepository.extractLocalFilePath(uri.scheme, uri.path)
            ?: return true
        val file = File(localFilePath)
        return ProfileRepository.isAvatarFileUsable(file.exists(), file.length())
    }

    private fun showEditUsernameDialog() {
        ProfileDialogs.showEditUsernameDialog(
            fragment = this,
            currentUsername = currentProfile?.username
        ) { newUsername ->
            if (newUsername.length >= 3) {
                updateUsername(newUsername)
            } else {
                showSnackbar(getString(R.string.profile_username_too_short), isError = true)
            }
        }
    }

    private fun getRankName(rank: Int) = when (rank) {
        1 -> getString(R.string.rank_1)
        2 -> getString(R.string.rank_2)
        3 -> getString(R.string.rank_3)
        else -> getString(R.string.rank_4)
    }

    private fun formatWholeNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)

    private fun showClassSelectionDialog(profile: Profile) {
        isClassDialogShowing = true
        ProfileDialogs.showClassSelectionDialog(
            fragment = this,
            onDismiss = {
                pendingClassSelectionDialog = null
                isClassDialogShowing = false
            }
        ) { className, dialog ->
            confirmClassSelection(profile, className, dialog)
        }
    }

    private fun confirmClassSelection(profile: Profile, className: String, parentDialog: androidx.appcompat.app.AlertDialog) {
        ProfileDialogs.showClassConfirmationDialog(
            fragment = this,
            className = className
        ) {
            pendingClassSelectionDialog = parentDialog
            viewModel.confirmClassSelection(profile, className)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    internal data class AvatarPresentationDecision(
        val displaySource: String?,
        val shouldClearPendingPreview: Boolean
    )

    companion object {
        internal fun resolveProfileHaloRes(streakCount: Int): Int? = when {
            streakCount >= 50 -> R.drawable.ic_streak_fire_50
            streakCount >= 25 -> R.drawable.ic_streak_fire_25
            streakCount >= 5 -> R.drawable.ic_streak_fire_5
            streakCount >= 1 -> R.drawable.ic_streak_fire
            else -> null
        }

        internal fun resolveAvatarPresentation(
            profileAvatarSource: String?,
            pendingAvatarPreviewSource: String?,
            pendingAvatarRemotePath: String?
        ): AvatarPresentationDecision {
            if (pendingAvatarPreviewSource.isNullOrBlank()) {
                return AvatarPresentationDecision(
                    displaySource = profileAvatarSource,
                    shouldClearPendingPreview = false
                )
            }

            val normalizedProfilePath = profileAvatarSource?.let { AvatarLoader.extractSupabaseStoragePath(it) }
            val isPendingUploadConfirmed = !pendingAvatarRemotePath.isNullOrBlank() &&
                normalizedProfilePath == pendingAvatarRemotePath

            return if (isPendingUploadConfirmed) {
                AvatarPresentationDecision(
                    displaySource = profileAvatarSource,
                    shouldClearPendingPreview = true
                )
            } else {
                AvatarPresentationDecision(
                    displaySource = pendingAvatarPreviewSource,
                    shouldClearPendingPreview = false
                )
            }
        }
    }
}

class BadgesAdapter : ListAdapter<String, BadgesAdapter.BadgeViewHolder>(BadgeDiffCallback()) {
    fun submitBadges(newBadges: List<String>) {
        submitList(newBadges.toList())
    }

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
        val badge = getItem(position)
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

    private class BadgeDiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
    }
}
