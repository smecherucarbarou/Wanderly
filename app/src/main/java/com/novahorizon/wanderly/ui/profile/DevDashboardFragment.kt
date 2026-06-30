package com.novahorizon.wanderly.ui.profile

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.notifications.NotificationCheckCoordinator
import com.novahorizon.wanderly.notifications.NotificationPermissionManager
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.ui.compose.screens.devdashboard.DevDashboardCallbacks
import com.novahorizon.wanderly.ui.compose.screens.devdashboard.DevDashboardDiagnostics
import com.novahorizon.wanderly.ui.compose.screens.devdashboard.DevDashboardRow
import com.novahorizon.wanderly.ui.compose.screens.devdashboard.DevDashboardSection
import com.novahorizon.wanderly.ui.compose.screens.devdashboard.DevDashboardScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.ui.main.MainNavCommand
import com.novahorizon.wanderly.ui.main.MainNavViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DevDashboardFragment : Fragment() {

    @Inject
    lateinit var repository: WanderlyRepository

    private val mainNav: MainNavViewModel by activityViewModels()

    private var accessVerified by mutableStateOf(false)
    private var diagnostics by mutableStateOf(DevDashboardDiagnostics())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            visibility = View.INVISIBLE
            setContent {
                WanderlyTheme {
                    if (accessVerified) {
                        DevDashboardScreen(
                            diagnostics = diagnostics,
                            isCrashlyticsEnabled = BuildConfig.DEBUG && BuildConfig.CRASH_REPORTING_CONFIGURED,
                            callbacks = DevDashboardCallbacks(
                                onRefreshDiagnostics = { refreshDiagnostics() },
                                onOpenNotificationSettings = { openNotificationSettings() },
                                onClearNotificationState = { triggerClearNotificationState() },
                                onCrashlyticsNonfatal = { triggerCrashlyticsNonfatal() }
                            )
                        )
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!BuildConfig.DEBUG) {
            showSnackbar(getString(R.string.admin_access_denied), isError = true)
            mainNav.send(MainNavCommand.Back)
            return
        }

        accessVerified = true
        view.visibility = View.VISIBLE
        refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        if (!accessVerified || !isAdded) return
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = repository.currentProfile.value ?: repository.getCurrentProfile()
            if (!isAdded) return@launch
            diagnostics = buildDiagnostics(profile)
        }
    }

    private fun buildDiagnostics(profile: Profile?): DevDashboardDiagnostics {
        val context = requireContext()
        val notificationStatus = when (NotificationPermissionManager.status(context)) {
            NotificationPermissionManager.Status.GRANTED -> getString(R.string.dev_dashboard_notification_granted)
            NotificationPermissionManager.Status.DENIED -> getString(R.string.dev_dashboard_notification_denied)
            NotificationPermissionManager.Status.NOT_REQUIRED -> getString(R.string.dev_dashboard_notification_not_required)
        }
        val systemNotifications = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            getString(R.string.dev_dashboard_enabled)
        } else {
            getString(R.string.dev_dashboard_disabled)
        }

        return DevDashboardDiagnostics(
            sections = listOf(
                DevDashboardSection(
                    title = getString(R.string.dev_dashboard_section_build_app),
                    rows = listOf(
                        DevDashboardRow(
                            getString(R.string.dev_dashboard_environment_label),
                            if (BuildConfig.DEBUG) getString(R.string.dev_dashboard_debug) else getString(R.string.dev_dashboard_release)
                        ),
                        DevDashboardRow(
                            getString(R.string.dev_dashboard_version_label),
                            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                        )
                    )
                ),
                DevDashboardSection(
                    title = getString(R.string.dev_dashboard_section_auth_session),
                    rows = listOf(
                        DevDashboardRow(
                            getString(R.string.dev_dashboard_auth_label),
                            if (profile == null) getString(R.string.dev_dashboard_profile_unavailable)
                            else getString(R.string.dev_dashboard_profile_loaded)
                        ),
                        DevDashboardRow(
                            getString(R.string.dev_dashboard_user_label),
                            redactIdentifier(profile?.id)
                        )
                    )
                ),
                DevDashboardSection(
                    title = getString(R.string.dev_dashboard_section_profile_avatar),
                    rows = listOf(
                        DevDashboardRow(
                            getString(R.string.dev_dashboard_avatar_label),
                            if (profile?.avatar_url.isNullOrBlank()) getString(R.string.dev_dashboard_none)
                            else getString(R.string.dev_dashboard_present)
                        )
                    )
                ),
                DevDashboardSection(
                    title = getString(R.string.dev_dashboard_section_friends_social),
                    rows = listOf(
                        DevDashboardRow(
                            getString(R.string.dev_dashboard_friend_code_label),
                            if (profile?.friend_code.isNullOrBlank()) getString(R.string.dev_dashboard_none)
                            else getString(R.string.dev_dashboard_present)
                        )
                    )
                ),
                DevDashboardSection(
                    title = getString(R.string.dev_dashboard_section_streak_notifications),
                    rows = listOf(
                        DevDashboardRow(
                            getString(R.string.dev_dashboard_notification_permission_label),
                            notificationStatus
                        ),
                        DevDashboardRow(
                            getString(R.string.dev_dashboard_system_notifications_label),
                            systemNotifications
                        )
                    )
                ),
                DevDashboardSection(
                    title = getString(R.string.dev_dashboard_section_storage_supabase),
                    rows = listOf(
                        DevDashboardRow(
                            getString(R.string.dev_dashboard_supabase_label),
                            redactedSupabaseHost()
                        )
                    )
                )
            )
        )
    }

    private fun triggerClearNotificationState() {
        viewLifecycleOwner.lifecycleScope.launch {
            WanderlyNotificationManager.clearNotificationCooldowns(requireContext())
            NotificationCheckCoordinator.clearCheckState(requireContext())
            refreshDiagnostics()
            showSnackbar(getString(R.string.dev_dashboard_cleared_notification_state))
        }
    }

    private fun triggerCrashlyticsNonfatal() {
        val recorded = CrashReporter.recordTestNonFatal(requireContext().applicationContext)
        showSnackbar(
            if (recorded) getString(R.string.dev_dashboard_crashlytics_nonfatal_queued)
            else getString(R.string.dev_dashboard_crashlytics_unavailable)
        )
    }

    private fun openNotificationSettings() {
        startActivity(NotificationPermissionManager.notificationSettingsIntent(requireContext()))
    }

    private fun redactedSupabaseHost(): String {
        val host = Uri.parse(BuildConfig.SUPABASE_URL).host
        return host?.takeIf { it.isNotBlank() } ?: getString(R.string.dev_dashboard_not_configured)
    }

    private fun redactIdentifier(raw: String?): String {
        val value = raw?.takeIf { it.isNotBlank() } ?: return getString(R.string.dev_dashboard_none)
        return if (value.length <= 8) {
            getString(R.string.dev_dashboard_redacted)
        } else {
            "${value.take(4)}...${value.takeLast(4)}"
        }
    }
}
