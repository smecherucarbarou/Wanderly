package com.novahorizon.wanderly.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.getValue
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.MainActivity
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.notifications.NotificationCheckCoordinator
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.ui.compose.screens.devdashboard.DevDashboardCallbacks
import com.novahorizon.wanderly.ui.compose.screens.devdashboard.DevDashboardScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.workers.SocialWorker
import com.novahorizon.wanderly.workers.StreakWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class DevDashboardFragment : Fragment() {

    @Inject
    lateinit var repository: WanderlyRepository
    private val prettyJson = Json { prettyPrint = true }
    private val adminToolsViewModel: AdminToolsViewModel by viewModels()
    private var accessVerified = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            visibility = View.INVISIBLE
            setContent {
                WanderlyTheme {
                    val aiState by adminToolsViewModel.aiNotificationState.asFlow()
                        .collectAsStateWithLifecycle(AdminToolsViewModel.AiNotificationState())

                    aiState.snackbarMessage?.let { message ->
                        showSnackbar(message.asString(requireContext()), isError = aiState.isError)
                        adminToolsViewModel.clearSnackbarMessage()
                    }

                    if (accessVerified) {
                        DevDashboardScreen(
                            aiLogs = aiState.logs.joinToString("\n\n") { it.asString(requireContext()) },
                            isAiRunning = aiState.isRunning,
                            isCrashlyticsEnabled = BuildConfig.CRASH_REPORTING_CONFIGURED,
                            callbacks = DevDashboardCallbacks(
                                onUpdateStats = { honey, streak, flights -> updateReality(honey, streak, flights) },
                                onNotifyStreak = { streak -> triggerNotifyStreak(streak) },
                                onResetDailyCooldown = {
                                    resetNotificationType(
                                        WanderlyNotificationManager.NotificationType.DAILY_REMINDER,
                                        getString(R.string.dev_dashboard_daily_reminder_label)
                                    )
                                },
                                onNotifyEvening = { triggerNotifyEvening() },
                                onResetEveningCooldown = {
                                    resetNotificationType(
                                        WanderlyNotificationManager.NotificationType.EVENING_ALERT,
                                        getString(R.string.dev_dashboard_evening_alert_label)
                                    )
                                },
                                onNotifyMilestone = { streak -> triggerNotifyMilestone(streak) },
                                onResetMilestoneCooldown = {
                                    resetNotificationType(
                                        WanderlyNotificationManager.NotificationType.MILESTONE,
                                        getString(R.string.dev_dashboard_milestone_label)
                                    )
                                },
                                onNotifyLost = { triggerNotifyLost() },
                                onResetLostCooldown = {
                                    resetNotificationType(
                                        WanderlyNotificationManager.NotificationType.STREAK_LOST,
                                        getString(R.string.dev_dashboard_streak_lost_label)
                                    )
                                },
                                onNotifyRival = { name -> triggerNotifyRival(name) },
                                onResetRivalCooldown = {
                                    resetNotificationType(
                                        WanderlyNotificationManager.NotificationType.RIVAL_ACTIVITY,
                                        getString(R.string.dev_dashboard_rival_activity_label)
                                    )
                                },
                                onNotifyOvertaken = { name -> triggerNotifyOvertaken(name) },
                                onResetOvertakenCooldown = {
                                    resetNotificationType(
                                        WanderlyNotificationManager.NotificationType.OVERTAKEN,
                                        getString(R.string.dev_dashboard_overtaken_label)
                                    )
                                },
                                onNotifyFight = { name -> triggerNotifyFight(name) },
                                onResetFightCooldown = {
                                    resetNotificationType(
                                        WanderlyNotificationManager.NotificationType.FIGHT_FOR_FIRST,
                                        getString(R.string.dev_dashboard_fight_label)
                                    )
                                },
                                onClearNotifCooldowns = { triggerClearAllCooldowns() },
                                onTestAiNotif = { adminToolsViewModel.runAiNotificationTest() },
                                onRawLogs = { showRawProfile() },
                                onResetVisit = { triggerResetVisit() },
                                onReplayOnboarding = { triggerReplayOnboarding() },
                                onRunWorkers = { triggerRunWorkers() },
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
        viewLifecycleOwner.lifecycleScope.launch {
            val isAdmin = repository.getCurrentProfile()?.admin_role == true
            if (!isAdmin) {
                showSnackbar(getString(R.string.admin_access_denied), isError = true)
                findNavController().navigateUp()
                return@launch
            }
            accessVerified = true
            view.visibility = View.VISIBLE
        }
    }

    private fun triggerNotifyStreak(streakText: String) {
        val streak = streakText.toIntOrNull()?.coerceAtLeast(1) ?: 7
        viewLifecycleOwner.lifecycleScope.launch {
            WanderlyNotificationManager.sendDailyReminder(requireContext(), streak, force = true)
            showSnackbar(resources.getQuantityString(R.plurals.dev_dashboard_forced_daily_reminder, streak, streak))
        }
    }

    private fun triggerNotifyEvening() {
        viewLifecycleOwner.lifecycleScope.launch {
            WanderlyNotificationManager.sendEveningAlert(requireContext(), force = true)
            showSnackbar(getString(R.string.dev_dashboard_forced_evening_alert))
        }
    }

    private fun triggerNotifyMilestone(streakText: String) {
        val streak = streakText.toIntOrNull()?.coerceAtLeast(1) ?: 10
        viewLifecycleOwner.lifecycleScope.launch {
            WanderlyNotificationManager.sendMilestoneCelebration(requireContext(), streak, force = true)
            showSnackbar(resources.getQuantityString(R.plurals.dev_dashboard_forced_milestone, streak, streak))
        }
    }

    private fun triggerNotifyLost() {
        viewLifecycleOwner.lifecycleScope.launch {
            WanderlyNotificationManager.sendStreakLost(requireContext(), force = true)
            showSnackbar(getString(R.string.dev_dashboard_forced_streak_lost))
        }
    }

    private fun triggerNotifyRival(name: String) {
        val rivalName = name.trim().ifBlank { getString(R.string.dev_dashboard_default_rival_one) }
        viewLifecycleOwner.lifecycleScope.launch {
            WanderlyNotificationManager.sendRivalActivity(requireContext(), rivalName, force = true)
            showSnackbar(getString(R.string.dev_dashboard_forced_rival_activity, rivalName))
        }
    }

    private fun triggerNotifyOvertaken(name: String) {
        val rivalName = name.trim().ifBlank { getString(R.string.dev_dashboard_default_rival_two) }
        viewLifecycleOwner.lifecycleScope.launch {
            WanderlyNotificationManager.sendOvertakenAlert(requireContext(), rivalName, force = true)
            showSnackbar(getString(R.string.dev_dashboard_forced_overtaken, rivalName))
        }
    }

    private fun triggerNotifyFight(name: String) {
        val rivalName = name.trim().ifBlank { getString(R.string.dev_dashboard_default_rival_three) }
        viewLifecycleOwner.lifecycleScope.launch {
            WanderlyNotificationManager.sendFightForFirst(requireContext(), rivalName, force = true)
            showSnackbar(getString(R.string.dev_dashboard_forced_fight, rivalName))
        }
    }

    private fun triggerClearAllCooldowns() {
        viewLifecycleOwner.lifecycleScope.launch {
            WanderlyNotificationManager.clearNotificationCooldowns(requireContext())
            NotificationCheckCoordinator.clearCheckState(requireContext())
            showSnackbar(getString(R.string.dev_dashboard_cleared_notification_state))
        }
    }

    private fun showRawProfile() {
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = repository.getCurrentProfile()
            if (profile != null) {
                AlertDialog.Builder(requireContext(), R.style.Wanderly_AlertDialog)
                    .setTitle(getString(R.string.dev_dashboard_live_profile_title))
                    .setMessage(prettyJson.encodeToString(profile))
                    .setPositiveButton(getString(R.string.dev_dashboard_ok), null)
                    .show()
            } else {
                showSnackbar(getString(R.string.dev_dashboard_failed_profile_fetch), isError = true)
            }
        }
    }

    private fun triggerResetVisit() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.updateLastVisitDate("2000-01-01")
            val success = repository.resetMissionDateForTesting()
            if (success) {
                showSnackbar(getString(R.string.dev_dashboard_reset_visit_success))
            } else {
                showSnackbar(getString(R.string.dev_dashboard_reset_visit_partial_failure), isError = true)
            }
        }
    }

    private fun triggerReplayOnboarding() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.setOnboardingSeen(false)
            startActivity(
                Intent(requireContext(), MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            requireActivity().finish()
        }
    }

    private fun triggerRunWorkers() {
        val socialRequest = OneTimeWorkRequestBuilder<SocialWorker>().build()
        val streakRequest = OneTimeWorkRequestBuilder<StreakWorker>().build()
        WorkManager.getInstance(requireContext()).enqueue(socialRequest)
        WorkManager.getInstance(requireContext()).enqueue(streakRequest)
        showSnackbar(getString(R.string.dev_dashboard_workers_queued))
    }

    private fun triggerCrashlyticsNonfatal() {
        val recorded = CrashReporter.recordTestNonFatal(requireContext().applicationContext)
        showSnackbar(
            if (recorded) getString(R.string.dev_dashboard_crashlytics_nonfatal_queued)
            else getString(R.string.dev_dashboard_crashlytics_unavailable)
        )
    }

    private fun resetNotificationType(
        type: WanderlyNotificationManager.NotificationType,
        label: String
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            WanderlyNotificationManager.clearNotificationCooldown(requireContext(), type)
            NotificationCheckCoordinator.clearCheckStateForType(requireContext(), type)
            showSnackbar(getString(R.string.dev_dashboard_cooldown_reset, label))
        }
    }

    private fun updateReality(honeyInput: String, streakInput: String, flightsInput: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = repository.getCurrentProfile() ?: return@launch

            val editHoney = honeyInput.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            val editFlights = flightsInput.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            val parsedStreak = streakInput.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

            if (honeyInput.trim().isNotEmpty() && editHoney == null) {
                showSnackbar(getString(R.string.dev_dashboard_honey_invalid), isError = true)
                return@launch
            }
            if (flightsInput.trim().isNotEmpty() && editFlights == null) {
                showSnackbar(getString(R.string.dev_dashboard_flights_invalid), isError = true)
                return@launch
            }
            if (streakInput.trim().isNotEmpty() && parsedStreak == null) {
                showSnackbar(getString(R.string.dev_dashboard_streak_invalid), isError = true)
                return@launch
            }

            val newHoney = editHoney ?: if (editFlights != null) editFlights * 50 else (profile.honey ?: 0)
            val newStreak = parsedStreak?.coerceAtLeast(0) ?: (profile.streak_count ?: 0)

            if (repository.adminUpdateProfileStats(profile.id, newHoney, newStreak)) {
                val refreshed = repository.getCurrentProfile() ?: profile.copy(
                    honey = newHoney,
                    streak_count = newStreak
                )
                showSnackbar(
                    getString(
                        R.string.dev_dashboard_reality_updated,
                        refreshed.honey ?: 0,
                        refreshed.streak_count ?: 0
                    )
                )
            } else {
                showSnackbar(getString(R.string.dev_dashboard_reality_update_failed), isError = true)
            }
        }
    }
}
