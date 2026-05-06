package com.novahorizon.wanderly.ui.profile

import com.novahorizon.wanderly.observability.AppLogger

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.auth.SessionNavigator
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileRepository
import com.novahorizon.wanderly.invites.InviteShareFormatter
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.ui.common.AvatarLoader
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.ui.compose.screens.profile.ProfileScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.widgets.StreakTierHelper
import com.yalantis.ucrop.UCrop
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var currentProfile: Profile? = null
    private var pendingAvatarDestinationUri: Uri? = null
    private var pendingAvatarPreviewSource: String? = null
    private var pendingAvatarRemotePath: String? = null
    private var pendingClassSelectionDialog: androidx.appcompat.app.AlertDialog? = null
    private var isClassDialogShowing = false
    private val viewModel: ProfileViewModel by viewModels()

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

        val resultUri = result.data?.let(UCrop::getOutput)
        if (resultUri == null) {
            Toast.makeText(requireContext(), R.string.profile_avatar_crop_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (!isUsableCropResult(resultUri)) {
            CrashReporter.recordNonFatal(
                CrashEvent.PROFILE_AVATAR_CROP_FAILED,
                IllegalStateException("avatar_crop_result_unusable"),
                CrashKey.COMPONENT to "profile",
                CrashKey.OPERATION to "avatar_crop"
            )
            if (BuildConfig.DEBUG) {
                AppLogger.e(
                    "ProfileFragment",
                    "Avatar crop result was empty or unreadable: ${LogRedactor.redact(resultUri.toString())}"
                )
            } else {
                AppLogger.e("ProfileFragment", "Avatar crop result was empty or unreadable")
            }
            Toast.makeText(requireContext(), R.string.profile_avatar_crop_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        uploadAvatarToSupabase(resultUri)
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult

        val imageCacheDir = File(requireContext().cacheDir, "images").apply {
            mkdirs()
        }

        val destinationFile = File.createTempFile(
            "avatar_crop_",
            ".jpg",
            imageCacheDir
        )

        val destinationUri = FileProvider.getUriForFile(
            requireContext(),
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            destinationFile
        )

        pendingAvatarDestinationUri = destinationUri

        val uCropIntent = UCrop.of(uri, destinationUri)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(400, 400)
            .withOptions(
                UCrop.Options().apply {
                    setCircleDimmedLayer(true)
                    setShowCropGrid(false)
                }
            )
            .getIntent(requireContext())
            .apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

        cropImage.launch(uCropIntent)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WanderlyTheme {
                    ProfileScreen(
                        viewModel = viewModel,
                        onLogout = { viewModel.logout() },
                        onSettings = {
                            findNavController().navigate(R.id.action_profile_to_devDashboard)
                        },
                        onEditAvatar = {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.profileEvent.observe(viewLifecycleOwner) { event ->
            when (event) {
                is ProfileViewModel.ProfileEvent.ShowMessage -> {
                    if (event.isError && pendingAvatarPreviewSource != null && pendingAvatarRemotePath == null) {
                        pendingAvatarPreviewSource = null
                        pendingAvatarRemotePath = null
                    }
                    showSnackbar(event.message.asString(requireContext()), isError = event.isError)
                    viewModel.clearProfileEvent()
                }

                is ProfileViewModel.ProfileEvent.AvatarUpdated -> {
                    val avatarUrl = event.avatarUrl
                    pendingAvatarPreviewSource = null
                    pendingAvatarRemotePath = AvatarLoader.extractSupabaseStoragePath(avatarUrl) ?: avatarUrl
                    currentProfile = currentProfile?.copy(avatar_url = avatarUrl)
                    showSnackbar(getString(R.string.profile_avatar_updated), isError = false)
                    viewModel.clearProfileEvent()
                }

                is ProfileViewModel.ProfileEvent.ClassLocked -> {
                    pendingClassSelectionDialog?.dismiss()
                    pendingClassSelectionDialog = null
                    currentProfile = currentProfile?.copy(explorer_class = event.className)
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

        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            if (profile != null) {
                currentProfile = profile
                val honey = profile.honey ?: 0
                val flights = honey / 50
                if (flights >= 10 && profile.explorer_class.isNullOrEmpty() && !isClassDialogShowing) {
                    showClassSelectionDialog(profile)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadProfile()
    }

    private fun uploadAvatarToSupabase(uri: Uri) {
        val profile = currentProfile ?: return
        pendingAvatarPreviewSource = uri.toString()
        pendingAvatarRemotePath = null
        viewModel.uploadAvatar(profile, uri)
    }

    private fun copyFriendCode(friendCode: String) {
        val clipboardManager = requireContext().getSystemService(ClipboardManager::class.java)
        clipboardManager?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.profile_friend_code_clip_label), friendCode)
        )
        showSnackbar(getString(R.string.friend_code_copied), isError = false)
    }

    private fun shareFriendCode(friendCode: String) {
        val shareMessage = InviteShareFormatter.format(friendCode)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareMessage)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.profile_share_friend_code)))
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
                viewModel.updateUsername(currentProfile!!, newUsername)
            } else {
                showSnackbar(getString(R.string.profile_username_too_short), isError = true)
            }
        }
    }

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

    internal data class ProfileHaloStyle(
        val glowRes: Int,
        val ringRes: Int,
        val accentRes: Int
    )

    companion object {
        internal fun resolveProfileHaloStyle(streakCount: Int): ProfileHaloStyle? {
            if (streakCount <= 0) return null

            return when (StreakTierHelper.resolve(streakCount).label) {
                "Starter" -> ProfileHaloStyle(
                    glowRes = R.drawable.ic_profile_streak_glow,
                    ringRes = R.drawable.ic_profile_streak_ring,
                    accentRes = R.drawable.ic_profile_streak_sparks
                )
                "Rising" -> ProfileHaloStyle(
                    glowRes = R.drawable.ic_profile_streak_glow_5,
                    ringRes = R.drawable.ic_profile_streak_ring_5,
                    accentRes = R.drawable.ic_profile_streak_sparks_5
                )
                "Blazing" -> ProfileHaloStyle(
                    glowRes = R.drawable.ic_profile_streak_glow_25,
                    ringRes = R.drawable.ic_profile_streak_ring_25,
                    accentRes = R.drawable.ic_profile_streak_sparks_25
                )
                else -> ProfileHaloStyle(
                    glowRes = R.drawable.ic_profile_streak_glow_50,
                    ringRes = R.drawable.ic_profile_streak_ring_50,
                    accentRes = R.drawable.ic_profile_streak_sparks_50
                )
            }
        }

        internal fun resolveStreakAccentColor(streakCount: Int): Int {
            return StreakTierHelper.resolve(streakCount).color
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

    internal data class AvatarPresentationDecision(
        val displaySource: String?,
        val shouldClearPendingPreview: Boolean
    )
}
