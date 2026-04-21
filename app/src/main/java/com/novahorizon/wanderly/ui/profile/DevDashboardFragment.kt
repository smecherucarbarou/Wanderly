package com.novahorizon.wanderly.ui.profile

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.api.GeminiClient
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.databinding.FragmentDevDashboardBinding
import com.novahorizon.wanderly.notifications.NotificationCheckCoordinator
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.ui.common.showSnackbar
import com.novahorizon.wanderly.workers.SocialWorker
import com.novahorizon.wanderly.workers.StreakWorker
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

class DevDashboardFragment : Fragment() {

    private var _binding: FragmentDevDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: WanderlyRepository
    private val prettyJson = Json { prettyPrint = true }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDevDashboardBinding.inflate(inflater, container, false)
        repository = WanderlyGraph.repository(requireContext())
        binding.root.visibility = View.INVISIBLE
        return binding.root
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
            binding.root.visibility = View.VISIBLE
            setupAdminTools()
        }
    }

    private fun setupAdminTools() {
        binding.tvAiLogs.movementMethod = ScrollingMovementMethod()

        binding.btnUpdateStats.setOnClickListener { updateReality() }

        binding.btnNotifyStreak.setOnClickListener {
            val streak = binding.editStreak.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 7
            WanderlyNotificationManager.sendDailyReminder(requireContext(), streak, force = true)
            announceTrigger(getString(R.string.dev_dashboard_forced_daily_reminder, streak))
        }
        binding.btnResetDailyCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.DAILY_REMINDER,
                getString(R.string.dev_dashboard_daily_reminder_label)
            )
        }

        binding.btnNotifyEvening.setOnClickListener {
            WanderlyNotificationManager.sendEveningAlert(requireContext(), force = true)
            announceTrigger(getString(R.string.dev_dashboard_forced_evening_alert))
        }
        binding.btnResetEveningCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.EVENING_ALERT,
                getString(R.string.dev_dashboard_evening_alert_label)
            )
        }

        binding.btnNotifyMilestone.setOnClickListener {
            val streak = binding.editStreak.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10
            WanderlyNotificationManager.sendMilestoneCelebration(requireContext(), streak, force = true)
            announceTrigger(getString(R.string.dev_dashboard_forced_milestone, streak))
        }
        binding.btnResetMilestoneCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.MILESTONE,
                getString(R.string.dev_dashboard_milestone_label)
            )
        }

        binding.btnNotifyLost.setOnClickListener {
            WanderlyNotificationManager.sendStreakLost(requireContext(), force = true)
            announceTrigger(getString(R.string.dev_dashboard_forced_streak_lost))
        }
        binding.btnResetLostCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.STREAK_LOST,
                getString(R.string.dev_dashboard_streak_lost_label)
            )
        }

        binding.btnNotifyRival.setOnClickListener {
            val name = rivalName(default = getString(R.string.dev_dashboard_default_rival_one))
            WanderlyNotificationManager.sendRivalActivity(requireContext(), name, force = true)
            announceTrigger(getString(R.string.dev_dashboard_forced_rival_activity, name))
        }
        binding.btnResetRivalCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.RIVAL_ACTIVITY,
                getString(R.string.dev_dashboard_rival_activity_label)
            )
        }

        binding.btnNotifyOvertaken.setOnClickListener {
            val name = rivalName(default = getString(R.string.dev_dashboard_default_rival_two))
            WanderlyNotificationManager.sendOvertakenAlert(requireContext(), name, force = true)
            announceTrigger(getString(R.string.dev_dashboard_forced_overtaken, name))
        }
        binding.btnResetOvertakenCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.OVERTAKEN,
                getString(R.string.dev_dashboard_overtaken_label)
            )
        }

        binding.btnNotifyFight.setOnClickListener {
            val name = rivalName(default = getString(R.string.dev_dashboard_default_rival_three))
            WanderlyNotificationManager.sendFightForFirst(requireContext(), name, force = true)
            announceTrigger(getString(R.string.dev_dashboard_forced_fight, name))
        }
        binding.btnResetFightCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.FIGHT_FOR_FIRST,
                getString(R.string.dev_dashboard_fight_label)
            )
        }

        binding.btnClearNotifCooldowns.setOnClickListener {
            WanderlyNotificationManager.clearNotificationCooldowns(requireContext())
            NotificationCheckCoordinator.clearCheckState(requireContext())
            announceTrigger(getString(R.string.dev_dashboard_cleared_notification_state))
        }

        binding.btnRawLogs.setOnClickListener {
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

        binding.btnTestAiNotif.setOnClickListener { runAiNotificationTest() }

        binding.btnResetVisit.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.updateLastVisitDate("2000-01-01")
                val success = repository.resetMissionDateForTesting()
                if (success) {
                    showSnackbar(getString(R.string.dev_dashboard_reset_visit_success), isError = false)
                } else {
                    showSnackbar(getString(R.string.dev_dashboard_reset_visit_partial_failure), isError = true)
                }
            }
        }

        binding.btnRunWorkers.setOnClickListener {
            val socialRequest = OneTimeWorkRequestBuilder<SocialWorker>().build()
            val streakRequest = OneTimeWorkRequestBuilder<StreakWorker>().build()

            WorkManager.getInstance(requireContext()).enqueue(socialRequest)
            WorkManager.getInstance(requireContext()).enqueue(streakRequest)

            announceTrigger(getString(R.string.dev_dashboard_workers_queued))
        }
    }

    private fun runAiNotificationTest() {
        binding.btnTestAiNotif.isEnabled = false
        binding.tvAiLogs.text = getString(R.string.dev_dashboard_ai_preview_started) + "\n\n"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = repository.getCurrentProfile()
                    ?: throw Exception(getString(R.string.dev_dashboard_no_live_profile))
                val payload = buildJsonObject {
                    put("trigger", getString(R.string.dev_dashboard_ai_trigger_daily))
                    put("current_streak", profile.streak_count ?: 0)
                    put("user_name", profile.username ?: getString(R.string.dev_dashboard_default_explorer))
                    put("honey_balance", profile.honey ?: 0)
                    put("hive_rank", profile.hive_rank ?: 1)
                }

                logToUi(
                    getString(
                        R.string.dev_dashboard_using_live_profile,
                        profile.username ?: getString(R.string.dev_dashboard_default_explorer)
                    )
                )
                logToUi(
                    getString(
                        R.string.dev_dashboard_live_profile_stats,
                        profile.streak_count ?: 0,
                        profile.honey ?: 0,
                        profile.hive_rank ?: 1
                    )
                )

                val prompt = """
                    Write one polished mobile push notification for Wanderly.
                    Context: $payload

                    Rules:
                    - Bee-themed, playful, but not cringe.
                    - Sound like a real production push notification.
                    - Title max 32 characters.
                    - Message max 110 characters.
                    - No hashtags, no emojis, no quotation marks.

                    Return ONLY raw JSON:
                    {"title":"Short title","message":"Short message"}
                """.trimIndent()

                val rawResponse = GeminiClient.generateText(prompt)
                val jsonStart = rawResponse.indexOf("{")
                val jsonEnd = rawResponse.lastIndexOf("}")
                if (jsonStart == -1 || jsonEnd == -1) {
                    throw Exception(getString(R.string.dev_dashboard_ai_invalid_json))
                }

                val parsed = JSONObject(rawResponse.substring(jsonStart, jsonEnd + 1))
                val title = parsed.optString("title").trim()
                    .ifBlank { getString(R.string.dev_dashboard_ai_default_title) }
                    .take(32)
                val message = parsed.optString("message").trim().ifBlank {
                    getString(R.string.dev_dashboard_ai_default_message)
                }.take(110)

                WanderlyNotificationManager.showNotification(
                    context = requireContext(),
                    title = title,
                    message = message,
                    notificationId = 3999,
                    dedupKey = "dev_ai_preview",
                    bypassCooldown = true
                )

                logToUi(getString(R.string.dev_dashboard_ai_final_title, title))
                logToUi(getString(R.string.dev_dashboard_ai_final_message, message))
                logToUi(getString(R.string.dev_dashboard_ai_sent_notice))
                showSnackbar(getString(R.string.dev_dashboard_ai_sent_success), isError = false)
            } catch (e: Exception) {
                val errorMessage = e.message ?: getString(R.string.error_network)
                logToUi(getString(R.string.dev_dashboard_ai_failed, errorMessage))
                showSnackbar(getString(R.string.dev_dashboard_ai_failed, errorMessage), isError = true)
            } finally {
                binding.btnTestAiNotif.isEnabled = true
            }
        }
    }

    private fun logToUi(message: String) {
        binding.tvAiLogs.append("$message\n\n")
        binding.tvAiLogs.post {
            val layout = binding.tvAiLogs.layout ?: return@post
            val scrollAmount = layout.getLineTop(binding.tvAiLogs.lineCount) - binding.tvAiLogs.height
            binding.tvAiLogs.scrollTo(0, scrollAmount.coerceAtLeast(0))
        }
    }

    private fun announceTrigger(message: String) {
        logToUi(message)
        showSnackbar(message, isError = false)
    }

    private fun resetNotificationType(
        type: WanderlyNotificationManager.NotificationType,
        label: String
    ) {
        WanderlyNotificationManager.clearNotificationCooldown(requireContext(), type)
        NotificationCheckCoordinator.clearCheckStateForType(requireContext(), type)
        announceTrigger(getString(R.string.dev_dashboard_cooldown_reset, label))
    }

    private fun rivalName(default: String): String {
        return binding.editFriendName.text.toString().trim().ifBlank { default }
    }

    private fun updateReality() {
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = repository.getCurrentProfile() ?: return@launch

            val honeyInput = binding.editHoney.text.toString().trim()
            val flightsInput = binding.editFlights.text.toString().trim()
            val streakInput = binding.editStreak.text.toString().trim()

            val editHoney = honeyInput.takeIf { it.isNotEmpty() }?.toIntOrNull()
            val editFlights = flightsInput.takeIf { it.isNotEmpty() }?.toIntOrNull()
            val parsedStreak = streakInput.takeIf { it.isNotEmpty() }?.toIntOrNull()

            if (honeyInput.isNotEmpty() && editHoney == null) {
                showSnackbar(getString(R.string.dev_dashboard_honey_invalid), isError = true)
                return@launch
            }

            if (flightsInput.isNotEmpty() && editFlights == null) {
                showSnackbar(getString(R.string.dev_dashboard_flights_invalid), isError = true)
                return@launch
            }

            if (streakInput.isNotEmpty() && parsedStreak == null) {
                showSnackbar(getString(R.string.dev_dashboard_streak_invalid), isError = true)
                return@launch
            }

            val newHoney = editHoney ?: if (editFlights != null) editFlights * 50 else profile.honey
            val newStreak = parsedStreak?.coerceAtLeast(0) ?: (profile.streak_count ?: 0)

            val updated = profile.copy(
                honey = newHoney,
                streak_count = newStreak
            )

            if (repository.updateProfile(updated)) {
                val refreshed = repository.getCurrentProfile() ?: updated
                showSnackbar(
                    getString(
                        R.string.dev_dashboard_reality_updated,
                        refreshed.honey ?: 0,
                        refreshed.streak_count ?: 0
                    ),
                    isError = false
                )
            } else {
                showSnackbar(getString(R.string.dev_dashboard_reality_update_failed), isError = true)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
