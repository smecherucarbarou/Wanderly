package com.novahorizon.wanderly.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.databinding.FragmentDevDashboardBinding
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.showSnackbar
import kotlinx.coroutines.launch
import android.text.method.ScrollingMovementMethod
import com.novahorizon.wanderly.api.GeminiClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import androidx.appcompat.app.AlertDialog
import com.novahorizon.wanderly.R

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.novahorizon.wanderly.workers.SocialWorker
import com.novahorizon.wanderly.workers.StreakWorker

class DevDashboardFragment : Fragment() {

    private var _binding: FragmentDevDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: WanderlyRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDevDashboardBinding.inflate(inflater, container, false)
        repository = WanderlyRepository(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnUpdateStats.setOnClickListener {
            updateReality()
        }

        binding.btnNotifyStreak.setOnClickListener {
            WanderlyNotificationManager.sendDailyReminder(requireContext(), 7)
            showSnackbar("Streak notification triggered", isError = false)
        }

        binding.btnNotifyMilestone.setOnClickListener {
            WanderlyNotificationManager.sendMilestoneCelebration(requireContext(), 10)
            showSnackbar("Milestone notification triggered", isError = false)
        }

        binding.btnNotifyLost.setOnClickListener {
            WanderlyNotificationManager.sendStreakLost(requireContext())
            showSnackbar("Streak Lost notification triggered", isError = false)
        }

        binding.btnNotifyRival.setOnClickListener {
            val name = binding.editFriendName.text.toString().ifEmpty { "BuzzyBee" }
            WanderlyNotificationManager.sendRivalActivity(requireContext(), name)
            showSnackbar("Rival notification triggered for $name", isError = false)
        }

        binding.btnNotifyOvertaken.setOnClickListener {
            val name = binding.editFriendName.text.toString().ifEmpty { "QueenExplorer" }
            WanderlyNotificationManager.sendOvertakenAlert(requireContext(), name)
            showSnackbar("Overtaken notification triggered for $name", isError = false)
        }

        binding.btnNotifyFight.setOnClickListener {
            val name = binding.editFriendName.text.toString().ifEmpty { "KingBee" }
            WanderlyNotificationManager.sendFightForFirst(requireContext(), name)
            showSnackbar("Fight notification triggered for $name", isError = false)
        }

        binding.btnRawLogs.setOnClickListener {
            lifecycleScope.launch {
                val profile = repository.getCurrentProfile()
                if (profile != null) {
                    val json = Json { prettyPrint = true }.encodeToString(profile)
                    AlertDialog.Builder(requireContext(), R.style.Wanderly_AlertDialog)
                        .setTitle("RAW HIVE PAYLOAD")
                        .setMessage(json)
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    showSnackbar("Failed to fetch payload", isError = true)
                }
            }
        }

        binding.tvAiLogs.movementMethod = ScrollingMovementMethod()
        binding.btnTestAiNotif.setOnClickListener {
            runAiNotificationTest()
        }
        
        binding.btnResetVisit.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.updateLastVisitDate("2000-01-01")
                val success = repository.resetMissionDateForTesting()
                if (success) {
                    showSnackbar("LOCAL & REMOTE dates reset to 2000! Reset your app now.", isError = false)
                } else {
                    showSnackbar("Local reset OK, but Supabase failed. Check connection.", isError = true)
                }
            }
        }

        binding.btnRunWorkers.setOnClickListener {
            val socialRequest = OneTimeWorkRequestBuilder<SocialWorker>().build()
            val streakRequest = OneTimeWorkRequestBuilder<StreakWorker>().build()
            
            WorkManager.getInstance(requireContext()).enqueue(socialRequest)
            WorkManager.getInstance(requireContext()).enqueue(streakRequest)
            
            showSnackbar("Workers forced to run NOW. Check Logcat for 'SocialWorker' or 'StreakWorker'", isError = false)
        }
    }

    private fun runAiNotificationTest() {
        binding.tvAiLogs.text = "--- NEW TEST (LIVE DATA) --- \n\n"
        lifecycleScope.launch {
            try {
                logToUi("Fetching live profile data...")
                val profile = repository.getCurrentProfile()

                // Step 1: Build Real Payload from database
                val realPayload = buildJsonObject {
                    put("trigger", "Daily Reminder")
                    put("current_streak", profile?.streak_count ?: 0)
                    put("user_name", profile?.username ?: "Explorer")
                    put("honey_balance", profile?.honey ?: 0)
                    put("hive_rank", profile?.hive_rank ?: 1)
                }
                logToUi("Payload Sent (Live Data): $realPayload")

                // Step 2: Call the Generator AI
                logToUi("Step 1: Calling Generator AI...")
                val generatorPrompt = """
                    You are the Wanderly App AI. Write a short, engaging push notification for a user.
                    Context: $realPayload
                    Style: Playful, bee-themed, encouraging.
                    Constraint: Max 120 characters.
                """.trimIndent()

                val generatorResponse = GeminiClient.model.generateContent(generatorPrompt)
                val generatedText = generatorResponse.text ?: "Error: No text generated"
                logToUi("Generator Output: $generatedText")

                // Step 3: Call the Auditor AI (The QA Step)
                logToUi("Step 2: Calling Auditor AI (QA)...")
                val auditorPrompt = """
                    You are the Wanderly QA Auditor. Evaluate the following notification text against the provided payload.
                    
                    PAYLOAD: $realPayload
                    TEXT TO AUDIT: "$generatedText"
                    
                    CRITERIA:
                    1. Accuracy: Does it reflect the streak/honey correctly?
                    2. Tone: Is it bee-themed and encouraging?
                    3. Length: Is it under 120 chars?
                    
                    Return your response in this EXACT format:
                    STATUS: [PASS or FAIL]
                    REASON: [Short explanation]
                    CORRECTED_VERSION: [The text, improved if needed, otherwise the same]
                """.trimIndent()

                val auditorResponse = GeminiClient.model.generateContent(auditorPrompt)
                val auditorResult = auditorResponse.text ?: "Error: No audit result"
                logToUi("Auditor Result:\n$auditorResult")

                // Step 4: Parse and Display
                val finalApproved = auditorResult.substringAfter("CORRECTED_VERSION:").trim()
                logToUi("Final Approved Text: $finalApproved")
                logToUi("--- TEST COMPLETE ---")

            } catch (e: Exception) {
                logToUi("CRITICAL ERROR: ${e.message}")
            }
        }
    }

    private fun logToUi(message: String) {
        binding.tvAiLogs.append("$message\n\n")
        // Automatic scroll to bottom
        val scrollAmount = binding.tvAiLogs.layout?.let { 
            it.getLineTop(binding.tvAiLogs.lineCount) - binding.tvAiLogs.height 
        } ?: 0
        if (scrollAmount > 0) {
            binding.tvAiLogs.scrollTo(0, scrollAmount)
        }
    }

    private fun updateReality() {
        lifecycleScope.launch {
            val profile = repository.getCurrentProfile() ?: return@launch
            
            val editHoney = binding.editHoney.text.toString().toIntOrNull()
            val editFlights = binding.editFlights.text.toString().toIntOrNull()
            
            val newHoney = editHoney ?: if (editFlights != null) editFlights * 50 else profile.honey
            val newStreak = binding.editStreak.text.toString().toIntOrNull() ?: profile.streak_count

            val updated = profile.copy(
                honey = newHoney,
                streak_count = newStreak
            )
            
            if (repository.updateProfile(updated)) {
                showSnackbar("Reality Altered! Honey: $newHoney, Streak: $newStreak", isError = false)
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
