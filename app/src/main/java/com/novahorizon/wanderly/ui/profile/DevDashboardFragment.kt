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
import com.novahorizon.wanderly.showSnackbar
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
            announceTrigger("Daily streak reminder forced for $streak days")
        }
        binding.btnResetDailyCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.DAILY_REMINDER,
                "Daily reminder"
            )
        }

        binding.btnNotifyEvening.setOnClickListener {
            WanderlyNotificationManager.sendEveningAlert(requireContext(), force = true)
            announceTrigger("Evening rescue alert forced")
        }
        binding.btnResetEveningCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.EVENING_ALERT,
                "Evening alert"
            )
        }

        binding.btnNotifyMilestone.setOnClickListener {
            val streak = binding.editStreak.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10
            WanderlyNotificationManager.sendMilestoneCelebration(requireContext(), streak, force = true)
            announceTrigger("Milestone alert forced for $streak days")
        }
        binding.btnResetMilestoneCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.MILESTONE,
                "Milestone celebration"
            )
        }

        binding.btnNotifyLost.setOnClickListener {
            WanderlyNotificationManager.sendStreakLost(requireContext(), force = true)
            announceTrigger("Streak lost alert forced")
        }
        binding.btnResetLostCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.STREAK_LOST,
                "Streak lost"
            )
        }

        binding.btnNotifyRival.setOnClickListener {
            val name = rivalName(default = "BuzzyBee")
            WanderlyNotificationManager.sendRivalActivity(requireContext(), name, force = true)
            announceTrigger("Rival activity forced for $name")
        }
        binding.btnResetRivalCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.RIVAL_ACTIVITY,
                "Rival activity"
            )
        }

        binding.btnNotifyOvertaken.setOnClickListener {
            val name = rivalName(default = "QueenExplorer")
            WanderlyNotificationManager.sendOvertakenAlert(requireContext(), name, force = true)
            announceTrigger("Overtaken alert forced for $name")
        }
        binding.btnResetOvertakenCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.OVERTAKEN,
                "Overtaken"
            )
        }

        binding.btnNotifyFight.setOnClickListener {
            val name = rivalName(default = "KingBee")
            WanderlyNotificationManager.sendFightForFirst(requireContext(), name, force = true)
            announceTrigger("Fight-for-first alert forced for $name")
        }
        binding.btnResetFightCooldown.setOnClickListener {
            resetNotificationType(
                WanderlyNotificationManager.NotificationType.FIGHT_FOR_FIRST,
                "Fight for first"
            )
        }

        binding.btnClearNotifCooldowns.setOnClickListener {
            WanderlyNotificationManager.clearNotificationCooldowns(requireContext())
            NotificationCheckCoordinator.clearCheckState(requireContext())
            announceTrigger("Notification cooldowns and check state cleared")
        }

        binding.btnRawLogs.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val profile = repository.getCurrentProfile()
                if (profile != null) {
                    AlertDialog.Builder(requireContext(), R.style.Wanderly_AlertDialog)
                        .setTitle("Live Profile JSON")
                        .setMessage(prettyJson.encodeToString(profile))
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    showSnackbar("Failed to fetch live profile", isError = true)
                }
            }
        }

        binding.btnTestAiNotif.setOnClickListener { runAiNotificationTest() }

        binding.btnResetVisit.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.updateLastVisitDate("2000-01-01")
                val success = repository.resetMissionDateForTesting()
                if (success) {
                    showSnackbar("Local and remote visit dates reset. Restart the app if needed.", isError = false)
                } else {
                    showSnackbar("Local reset worked, but the remote reset failed.", isError = true)
                }
            }
        }

        binding.btnRunWorkers.setOnClickListener {
            val socialRequest = OneTimeWorkRequestBuilder<SocialWorker>().build()
            val streakRequest = OneTimeWorkRequestBuilder<StreakWorker>().build()

            WorkManager.getInstance(requireContext()).enqueue(socialRequest)
            WorkManager.getInstance(requireContext()).enqueue(streakRequest)

            announceTrigger("SocialWorker and StreakWorker were queued immediately")
        }
    }

    private fun runAiNotificationTest() {
        binding.btnTestAiNotif.isEnabled = false
        binding.tvAiLogs.text = "AI notification preview started.\n\n"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = repository.getCurrentProfile() ?: throw Exception("No live profile available.")
                val payload = buildJsonObject {
                    put("trigger", "Daily Reminder")
                    put("current_streak", profile.streak_count ?: 0)
                    put("user_name", profile.username ?: "Explorer")
                    put("honey_balance", profile.honey ?: 0)
                    put("hive_rank", profile.hive_rank ?: 1)
                }

                logToUi("Using live profile for ${profile.username ?: "Explorer"}")
                logToUi("Streak: ${profile.streak_count ?: 0} | Honey: ${profile.honey ?: 0} | Rank: ${profile.hive_rank ?: 1}")

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
                if (jsonStart == -1 || jsonEnd == -1) throw Exception("AI did not return valid JSON.")

                val parsed = JSONObject(rawResponse.substring(jsonStart, jsonEnd + 1))
                val title = parsed.optString("title").trim().ifBlank { "Hive update" }.take(32)
                val message = parsed.optString("message").trim().ifBlank {
                    "Your streak is waiting. One quick mission keeps the hive alive."
                }.take(110)

                WanderlyNotificationManager.showNotification(
                    context = requireContext(),
                    title = title,
                    message = message,
                    notificationId = 3999,
                    dedupKey = "dev_ai_preview",
                    bypassCooldown = true
                )

                logToUi("Final title: $title")
                logToUi("Final message: $message")
                logToUi("Sent as a real local notification with cooldown bypass.")
                showSnackbar("AI reminder generated and sent", isError = false)
            } catch (e: Exception) {
                logToUi("AI preview failed: ${e.message}")
                showSnackbar("AI preview failed: ${e.message}", isError = true)
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
        announceTrigger("$label cooldown/state reset")
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
                showSnackbar("Honey must be a valid number.", isError = true)
                return@launch
            }

            if (flightsInput.isNotEmpty() && editFlights == null) {
                showSnackbar("Flights must be a valid number.", isError = true)
                return@launch
            }

            if (streakInput.isNotEmpty() && parsedStreak == null) {
                showSnackbar("Streak must be a valid number.", isError = true)
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
                    "Reality altered. Honey: ${refreshed.honey ?: 0}, Streak: ${refreshed.streak_count ?: 0}",
                    isError = false
                )
            } else {
                showSnackbar("Failed to alter reality.", isError = true)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
