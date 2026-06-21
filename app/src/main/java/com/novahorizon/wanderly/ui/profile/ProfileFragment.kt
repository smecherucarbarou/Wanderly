package com.novahorizon.wanderly.ui.profile

import com.novahorizon.wanderly.observability.AppLogger

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.MainActivity
import com.novahorizon.wanderly.auth.SessionNavigator
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileRepository
import com.novahorizon.wanderly.invites.InviteShareFormatter
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.notifications.NotificationPermissionManager
import com.novahorizon.wanderly.ui.common.AvatarLoader
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.ui.compose.screens.profile.ProfileScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.widgets.StreakTierHelper
import com.yalantis.ucrop.UCrop
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var currentProfile: Profile? = null
    private var pendingAvatarDestinationUri: Uri? = null
    private var pendingAvatarPreviewSource: String? = null
    private var pendingAvatarRemotePath: String? = null
    private var pendingClassSelectionDialog: androidx.appcompat.app.AlertDialog? = null
    private var isClassDialogShowing = false
    private var avatarDisplaySource by mutableStateOf<String?>(null)
    private var showDevPanel by mutableStateOf(BuildConfig.DEBUG)
    private var notificationStatusText by mutableStateOf<String?>(null)
    private var notificationActionLabel by mutableStateOf<String?>(null)
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
        val appContext = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            val destinationUri = withContext(Dispatchers.IO) {
                createAvatarCropDestinationUri(appContext)
            }
            if (!isAdded) return@launch

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
                        avatarDisplaySource = avatarDisplaySource,
                        showDevPanel = showDevPanel,
                        notificationStatusText = notificationStatusText,
                        notificationActionLabel = notificationActionLabel,
                        onLogout = { viewModel.logout() },
                        onSettings = { openDevDashboard() },
                        onNotificationAction = { handleNotificationAction() },
                        onEditAvatar = {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onEditUsername = { showEditUsernameDialog() },
                        onCopyFriendCode = { copyFriendCode(it) },
                        onShareFriendCode = { shareFriendCode(it) },
                        onUseStreakFreeze = { viewModel.useStreakFreeze() },
                        onClaimMilestone = { viewModel.claimStreakMilestone(it) },
                        onClaimReferral = { viewModel.claimReferral(it) },
                        onRetry = { viewModel.loadProfile() }
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
                        updateAvatarDisplaySource(currentProfile)
                    }
                    showSnackbar(event.message.asString(requireContext()), isError = event.isError)
                    viewModel.clearProfileEvent()
                }

                is ProfileViewModel.ProfileEvent.AvatarUpdated -> {
                    val avatarUrl = event.avatarUrl
                    pendingAvatarRemotePath = AvatarLoader.extractSupabaseStoragePath(avatarUrl) ?: avatarUrl
                    currentProfile = currentProfile?.copy(avatar_url = avatarUrl)
                    updateAvatarDisplaySource(currentProfile)
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
                showDevPanel = shouldShowDevPanel(BuildConfig.DEBUG, profile.admin_role)
                updateAvatarDisplaySource(profile)
                val honey = profile.honey ?: 0
                val flights = honey / 50
                if (shouldPromptClassSelection(flights, profile.explorer_class, isClassDialogShowing)) {
                    showClassSelectionDialog(profile)
                }
            } else {
                currentProfile = null
                showDevPanel = shouldShowDevPanel(BuildConfig.DEBUG, null)
                updateAvatarDisplaySource(null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationUiState()
        viewModel.loadProfile()
    }

    private fun refreshNotificationUiState() {
        if (!isAdded) return
        val context = requireContext()
        val status = NotificationPermissionManager.status(context)
        val systemNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

        if (
            systemNotificationsEnabled &&
            (status == NotificationPermissionManager.Status.GRANTED || status == NotificationPermissionManager.Status.NOT_REQUIRED)
        ) {
            notificationStatusText = null
            notificationActionLabel = null
            return
        }

        notificationStatusText = if (!systemNotificationsEnabled) {
            getString(R.string.profile_notifications_system_disabled)
        } else {
            getString(R.string.profile_notifications_permission_denied)
        }

        notificationActionLabel = if (
            status == NotificationPermissionManager.Status.DENIED &&
            !NotificationPermissionManager.hasRequestedPermissionBefore(context)
        ) {
            getString(R.string.profile_notifications_enable)
        } else {
            getString(R.string.profile_notifications_open_settings)
        }
    }

    private fun handleNotificationAction() {
        val context = requireContext()
        val shouldRequestInApp = NotificationPermissionManager.status(context) == NotificationPermissionManager.Status.DENIED &&
            !NotificationPermissionManager.hasRequestedPermissionBefore(context)

        if (shouldRequestInApp) {
            (activity as? MainActivity)?.requestNotificationPermissionIfNeeded()
        } else {
            startActivity(NotificationPermissionManager.notificationSettingsIntent(context))
        }
    }

    private fun uploadAvatarToSupabase(uri: Uri) {
        val profile = currentProfile ?: viewModel.profile.value
        if (profile == null) {
            pendingAvatarPreviewSource = null
            pendingAvatarRemotePath = null
            showSnackbar(getString(R.string.profile_load_failed), isError = true)
            viewModel.loadProfile()
            return
        }
        pendingAvatarPreviewSource = uri.toString()
        pendingAvatarRemotePath = null
        updateAvatarDisplaySource(profile)
        viewModel.uploadAvatar(profile, uri)
    }

    private fun createAvatarCropDestinationUri(context: Context): Uri {
        val imageCacheDir = File(context.cacheDir, "images").apply {
            mkdirs()
        }
        val destinationFile = File.createTempFile(
            "avatar_crop_",
            ".jpg",
            imageCacheDir
        )
        return FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            destinationFile
        )
    }

    private fun updateAvatarDisplaySource(profile: Profile?) {
        val decision = resolveAvatarPresentation(
            profileAvatarSource = profile?.avatar_url,
            pendingAvatarPreviewSource = pendingAvatarPreviewSource,
            pendingAvatarRemotePath = pendingAvatarRemotePath
        )
        if (decision.shouldClearPendingPreview) {
            pendingAvatarPreviewSource = null
            pendingAvatarRemotePath = null
        }
        avatarDisplaySource = decision.displaySource
    }

    private fun openDevDashboard() {
        if (!BuildConfig.DEBUG) return

        val navController = runCatching { findNavController() }
            .getOrElse { error ->
                logDevPanelNavigationFailure(error)
                showSnackbar(getString(R.string.dev_dashboard_open_failed), isError = true)
                return
            }

        if (navController.currentDestination?.id == R.id.devDashboardFragment) return

        runCatching {
            if (navController.currentDestination?.id == R.id.profileFragment) {
                navController.navigate(R.id.action_profile_to_devDashboard)
            } else {
                navController.navigate(R.id.devDashboardFragment)
            }
        }.recoverCatching {
            navController.navigate(R.id.devDashboardFragment)
        }.onFailure { error ->
            logDevPanelNavigationFailure(error)
            showSnackbar(getString(R.string.dev_dashboard_open_failed), isError = true)
        }
    }

    private fun logDevPanelNavigationFailure(error: Throwable) {
        if (BuildConfig.DEBUG) {
            AppLogger.e("ProfileFragment", "Dev Panel navigation failed: ${LogRedactor.redact(error.message)}")
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
                val profile = currentProfile ?: return@showEditUsernameDialog
                viewModel.updateUsername(profile, newUsername)
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

        internal fun shouldShowDevPanel(isDebugBuild: Boolean, @Suppress("UNUSED_PARAMETER") isAdmin: Boolean?): Boolean {
            return isDebugBuild
        }

        internal fun shouldPromptClassSelection(
            @Suppress("UNUSED_PARAMETER") flights: Int,
            @Suppress("UNUSED_PARAMETER") explorerClass: String?,
            @Suppress("UNUSED_PARAMETER") isDialogShowing: Boolean
        ): Boolean = false

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
