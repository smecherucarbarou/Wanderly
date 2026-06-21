package com.novahorizon.wanderly.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.data.ai.AiAssistantException
import com.novahorizon.wanderly.data.ai.AiAssistantRepository
import com.novahorizon.wanderly.data.ai.AiChatMessage
import com.novahorizon.wanderly.data.ai.AiChatRole
import com.novahorizon.wanderly.data.ai.AiGuideContext
import com.novahorizon.wanderly.data.ai.AiQuotaResult
import com.novahorizon.wanderly.data.plus.PlusEntitlement
import com.novahorizon.wanderly.data.plus.PlusRepository
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class WanderlyGuideUiState {
    data object Idle : WanderlyGuideUiState()
    data class Loading(
        val messages: List<AiChatMessage>,
        val entitlement: PlusEntitlement?,
        val quota: AiQuotaResult?
    ) : WanderlyGuideUiState()

    data class Ready(
        val messages: List<AiChatMessage>,
        val entitlement: PlusEntitlement?,
        val quota: AiQuotaResult?
    ) : WanderlyGuideUiState()

    data class QuotaExceeded(
        val messages: List<AiChatMessage>,
        val quota: AiQuotaResult,
        val entitlement: PlusEntitlement?
    ) : WanderlyGuideUiState()

    data class Error(
        val messages: List<AiChatMessage>,
        val message: String,
        val entitlement: PlusEntitlement?
    ) : WanderlyGuideUiState()

    data object Unauthenticated : WanderlyGuideUiState()
}

@HiltViewModel
class WanderlyGuideViewModel @Inject constructor(
    private val assistantRepository: AiAssistantRepository,
    private val plusRepository: PlusRepository,
    private val locationProvider: GuideLocationProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<WanderlyGuideUiState>(WanderlyGuideUiState.Idle)
    val uiState: StateFlow<WanderlyGuideUiState> = _uiState.asStateFlow()

    private var messages: List<AiChatMessage> = emptyList()
    private var entitlement: PlusEntitlement? = null
    private var isSending = false

    init {
        loadInitialState()
    }

    fun refreshEntitlement() {
        loadInitialState()
    }

    fun sendMessage(message: String) {
        val trimmedMessage = message.trim()
        if (trimmedMessage.isBlank() || isSending) return
        if (_uiState.value == WanderlyGuideUiState.Unauthenticated) return
        if (_uiState.value is WanderlyGuideUiState.QuotaExceeded) return

        isSending = true
        viewModelScope.launch {
            var outgoingMessages = messages
            try {
                if (!assistantRepository.isAuthenticated()) {
                    _uiState.value = WanderlyGuideUiState.Unauthenticated
                    return@launch
                }

                val userMessage = AiChatMessage(
                    role = AiChatRole.USER,
                    content = trimmedMessage
                )
                outgoingMessages = messages + userMessage
                _uiState.value = WanderlyGuideUiState.Loading(
                    messages = outgoingMessages,
                    entitlement = entitlement,
                    quota = assistantRepository.latestQuota
                )

                assistantRepository.sendMessage(
                    message = trimmedMessage,
                    history = messages,
                    context = buildGuideContext(trimmedMessage)
                ).fold(
                    onSuccess = { assistantText ->
                        val assistantMessage = AiChatMessage(
                            role = AiChatRole.ASSISTANT,
                            content = assistantText
                        )
                        messages = outgoingMessages + assistantMessage
                        _uiState.value = WanderlyGuideUiState.Ready(
                            messages = messages,
                            entitlement = entitlement,
                            quota = assistantRepository.latestQuota
                        )
                    },
                    onFailure = { error ->
                        handleSendFailure(error, outgoingMessages)
                    }
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                handleSendFailure(e, outgoingMessages)
            } finally {
                isSending = false
            }
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            try {
                if (!assistantRepository.isAuthenticated()) {
                    _uiState.value = WanderlyGuideUiState.Unauthenticated
                    return@launch
                }

                entitlement = plusRepository.getMyEntitlement()
                    .getOrElse { error ->
                        logWarning("Plus entitlement unavailable", error)
                        PlusEntitlement.free()
                    }

                _uiState.value = WanderlyGuideUiState.Ready(
                    messages = messages,
                    entitlement = entitlement,
                    quota = assistantRepository.latestQuota
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logWarning("Guide initial state failed", e)
                _uiState.value = WanderlyGuideUiState.Error(
                    messages = messages,
                    message = AiAssistantException.SAFE_BACKEND_ERROR,
                    entitlement = entitlement
                )
            }
        }
    }

    private suspend fun buildGuideContext(message: String): AiGuideContext {
        if (!guideMessageNeedsLocationContext(message)) return AiGuideContext()

        val locationContext = try {
            locationProvider.getApproximateLocationContext()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logWarning("Guide location context unavailable", e)
            null
        }

        if (guideMessageNeedsStrictCityContext(message)) {
            val cityDisplay = locationContext?.cityDisplayName()
            return if (cityDisplay.isNullOrBlank()) {
                AiGuideContext(
                    tripContext = "Ask the user which city to use before recommending hidden gems."
                )
            } else {
                AiGuideContext(
                    currentCity = locationContext.city,
                    currentAdminArea = locationContext.adminArea,
                    currentCountry = locationContext.country,
                    coarseCoordinates = locationContext.coarseCoordinates,
                    tripContext = "Current city: $cityDisplay. Recommend places inside this city only, not nearby towns or villages."
                )
            }
        }

        return AiGuideContext(
            approximateLocation = locationContext?.coarseCoordinates?.let { "near $it" },
            currentCity = locationContext?.city,
            currentAdminArea = locationContext?.adminArea,
            currentCountry = locationContext?.country,
            coarseCoordinates = locationContext?.coarseCoordinates
        )
    }

    private fun handleSendFailure(error: Throwable, outgoingMessages: List<AiChatMessage>) {
        when (error) {
            is AiAssistantException.Unauthenticated -> {
                _uiState.value = WanderlyGuideUiState.Unauthenticated
            }

            is AiAssistantException.QuotaExceeded -> {
                messages = outgoingMessages
                _uiState.value = WanderlyGuideUiState.QuotaExceeded(
                    messages = messages,
                    quota = error.quota,
                    entitlement = entitlement
                )
            }

            else -> {
                if (error !is AiAssistantException.BackendError) {
                    logWarning("Guide send failed", error)
                }
                messages = outgoingMessages
                _uiState.value = WanderlyGuideUiState.Error(
                    messages = messages,
                    message = error.message?.takeIf { it.isNotBlank() }
                        ?: AiAssistantException.SAFE_BACKEND_ERROR,
                    entitlement = entitlement
                )
            }
        }
    }

    private fun logWarning(message: String, throwable: Throwable) {
        AppLogger.w(
            TAG,
            "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
        )
    }

    private companion object {
        const val TAG = "WanderlyGuideViewModel"
    }
}

internal fun guideMessageNeedsLocationContext(message: String): Boolean {
    val lower = message.lowercase()
    return listOf(
        "nearby",
        "near me",
        "around me",
        "around here",
        "close by",
        "cheap food",
        "restaurant",
        "restaurants",
        "cafe",
        "cafes",
        "hidden gems",
        "hidden gem",
        "rainy-day",
        "rainy day"
    ).any(lower::contains)
}

internal fun guideMessageNeedsStrictCityContext(message: String): Boolean {
    val lower = message.lowercase()
    return listOf(
        "hidden gems",
        "hidden gem",
        "gems around me",
        "gems near me"
    ).any(lower::contains)
}
