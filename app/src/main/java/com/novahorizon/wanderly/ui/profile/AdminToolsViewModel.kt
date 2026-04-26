package com.novahorizon.wanderly.ui.profile

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.api.GeminiClient
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

class AdminToolsViewModel(private val repository: WanderlyRepository) : ViewModel() {
    data class AiNotificationState(
        val isRunning: Boolean = false,
        val logs: List<String> = emptyList(),
        val snackbarMessage: String? = null,
        val isError: Boolean = false
    )

    private val _aiNotificationState = MutableLiveData(AiNotificationState())
    val aiNotificationState: LiveData<AiNotificationState> = _aiNotificationState

    fun runAiNotificationTest() {
        _aiNotificationState.value = AiNotificationState(
            isRunning = true,
            logs = listOf(repository.context.getString(R.string.dev_dashboard_ai_preview_started))
        )

        viewModelScope.launch {
            try {
                val profile = repository.getCurrentProfile()
                    ?: throw Exception(repository.context.getString(R.string.dev_dashboard_no_live_profile))
                val payload = buildJsonObject {
                    put("trigger", repository.context.getString(R.string.dev_dashboard_ai_trigger_daily))
                    put("current_streak", profile.streak_count ?: 0)
                    put("user_name", profile.username ?: repository.context.getString(R.string.dev_dashboard_default_explorer))
                    put("honey_balance", profile.honey ?: 0)
                    put("hive_rank", profile.hive_rank ?: 1)
                }

                addLog(
                    repository.context.getString(
                        R.string.dev_dashboard_using_live_profile,
                        profile.username ?: repository.context.getString(R.string.dev_dashboard_default_explorer)
                    )
                )
                addLog(
                    repository.context.getString(
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
                    throw Exception(repository.context.getString(R.string.dev_dashboard_ai_invalid_json))
                }

                val parsed = JSONObject(rawResponse.substring(jsonStart, jsonEnd + 1))
                val title = parsed.optString("title").trim()
                    .ifBlank { repository.context.getString(R.string.dev_dashboard_ai_default_title) }
                    .take(32)
                val message = parsed.optString("message").trim().ifBlank {
                    repository.context.getString(R.string.dev_dashboard_ai_default_message)
                }.take(110)

                WanderlyNotificationManager.showNotification(
                    context = repository.context,
                    title = title,
                    message = message,
                    notificationId = 3999,
                    dedupKey = "dev_ai_preview",
                    bypassCooldown = true
                )

                addLog(repository.context.getString(R.string.dev_dashboard_ai_final_title, title))
                addLog(repository.context.getString(R.string.dev_dashboard_ai_final_message, message))
                addLog(repository.context.getString(R.string.dev_dashboard_ai_sent_notice))
                finish(repository.context.getString(R.string.dev_dashboard_ai_sent_success), isError = false)
            } catch (e: Exception) {
                val errorMessage = e.message ?: repository.context.getString(R.string.error_network)
                addLog(repository.context.getString(R.string.dev_dashboard_ai_failed, errorMessage))
                finish(repository.context.getString(R.string.dev_dashboard_ai_failed, errorMessage), isError = true)
            }
        }
    }

    fun clearSnackbarMessage() {
        val current = _aiNotificationState.value ?: return
        _aiNotificationState.value = current.copy(snackbarMessage = null)
    }

    private fun addLog(message: String) {
        val current = _aiNotificationState.value ?: AiNotificationState(isRunning = true)
        _aiNotificationState.postValue(current.copy(logs = current.logs + message))
    }

    private fun finish(message: String, isError: Boolean) {
        val current = _aiNotificationState.value ?: AiNotificationState()
        _aiNotificationState.postValue(
            current.copy(
                isRunning = false,
                snackbarMessage = message,
                isError = isError
            )
        )
    }
}
