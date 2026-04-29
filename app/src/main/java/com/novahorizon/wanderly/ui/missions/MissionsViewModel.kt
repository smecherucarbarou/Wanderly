package com.novahorizon.wanderly.ui.missions

import com.novahorizon.wanderly.observability.AppLogger

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.MissionDetailsRepository
import com.novahorizon.wanderly.data.MissionCompletionResult
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.data.derivedHiveRank
import com.novahorizon.wanderly.data.mission.MissionCandidateProvider
import com.novahorizon.wanderly.data.mission.MissionPlaceSelectionResult
import com.novahorizon.wanderly.data.mission.MissionPlaceSelecting
import com.novahorizon.wanderly.data.mission.MissionPlaceSelector
import com.novahorizon.wanderly.data.mission.ValidatedMissionPlace
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.ui.common.UiText
import com.novahorizon.wanderly.util.AiResponseParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MissionsViewModel @Inject constructor(
    private val repository: WanderlyRepository,
    private val savedStateHandle: SavedStateHandle,
    private val profileStateProvider: ProfileStateProvider,
    private val missionDetailsRepository: MissionDetailsRepository,
    private val missionCandidateProvider: MissionCandidateProvider,
    private val missionPlaceSelector: MissionPlaceSelecting
) : ViewModel() {

    private val _profile = MutableLiveData<Profile?>()
    val profile: LiveData<Profile?> = _profile

    private val _missionState = MutableLiveData<MissionState>(restoreMissionState())
    val missionState: LiveData<MissionState> = _missionState

    private val _streakMessage = MutableLiveData<String?>()
    val streakMessage: LiveData<String?> = _streakMessage

    private var missionGenerationInFlight = false
    private var profileCollectorJob: Job? = null
    private var currentMission: String?
        get() = savedStateHandle[KEY_CURRENT_MISSION]
        set(value) {
            if (value == null) {
                savedStateHandle.remove<String>(KEY_CURRENT_MISSION)
            } else {
                savedStateHandle[KEY_CURRENT_MISSION] = value
            }
        }
    private var verificationStep: String
        get() = savedStateHandle[KEY_VERIFICATION_STEP] ?: VERIFICATION_STEP_IDLE
        set(value) {
            savedStateHandle[KEY_VERIFICATION_STEP] = value
        }

    init {
        startProfileCollector()
    }

    sealed class MissionState {
        object Idle : MissionState()
        object Generating : MissionState()
        data class MissionReceived(val text: String) : MissionState()
        object Verifying : MissionState()
        data class VerificationResult(val success: Boolean, val message: UiText) : MissionState()
        object Completing : MissionState()
        data class Error(val message: UiText) : MissionState()
        object FetchingDetails : MissionState()
        data class DetailsReceived(val info: String) : MissionState()
    }

    fun loadProfile() {
        if (profileCollectorJob?.isActive != true) {
            startProfileCollector()
        }
        viewModelScope.launch {
            profileStateProvider.refreshProfile()
        }
    }

    fun generateMission(lat: Double, lng: Double, city: String?) {
        if (missionGenerationInFlight) {
            if (BuildConfig.DEBUG) {
                AppLogger.w("MissionsViewModel", "Ignoring duplicate mission generation request.")
            }
            return
        }
        missionGenerationInFlight = true
        _missionState.postValue(MissionState.Generating)

        viewModelScope.launch {
            try {
                val currentProfile = profileStateProvider.refreshProfile()
                val rank = currentProfile?.derivedHiveRank() ?: 1
                val radiusMeters = HiveRank.missionRadiusMeters(rank)
                val history = repository.getMissionHistory()
                val promptCity = city?.trim().orEmpty()
                val missionType = DEFAULT_MISSION_TYPE
                val desiredRadiusKm = maxOf(
                    radiusMeters / 1000.0,
                    MissionPlaceSelector.LANDMARK_RADIUS.preferredMeters / 1000.0
                )

                val selection = runCatching {
                    val candidates = missionCandidateProvider.generateCandidates(
                        city = promptCity,
                        latitude = lat,
                        longitude = lng,
                        radiusKm = desiredRadiusKm,
                        missionType = missionType
                    )
                    missionPlaceSelector.selectBestMissionPlace(
                        userLat = lat,
                        userLng = lng,
                        city = promptCity,
                        countryRegion = null,
                        missionType = missionType,
                        candidates = candidates
                    )
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    logFallback("Mission place selection failed; using nearby exploration fallback.", error)
                }.getOrElse {
                    MissionPlaceSelectionResult.Fallback("provider unavailable")
                }

                val preparedMission = when (selection) {
                    is MissionPlaceSelectionResult.Success -> buildValidatedPlaceMission(selection.place, history)
                    is MissionPlaceSelectionResult.Fallback -> {
                        logFallback("Could not verify a specific mission destination; using nearby exploration fallback. reason=${selection.reason}")
                        buildFallbackMission(
                            history = history,
                            city = city,
                            lat = lat,
                            lng = lng
                        )
                    }
                }

                repository.saveMissionData(
                    text = preparedMission.text,
                    target = preparedMission.target,
                    history = preparedMission.history,
                    city = city,
                    targetLat = preparedMission.targetLat,
                    targetLng = preparedMission.targetLng
                )
                currentMission = preparedMission.text
                verificationStep = VERIFICATION_STEP_RECEIVED
                _missionState.postValue(MissionState.MissionReceived(preparedMission.text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postGenericMissionError("Mission generation failed", e)
            } finally {
                missionGenerationInFlight = false
            }
        }
    }

    private fun buildValidatedPlaceMission(
        place: ValidatedMissionPlace,
        history: String
    ): PreparedMission {
        val verifiedTargetName = place.placesName
        val verifiedMissionText =
            "Go to $verifiedTargetName and take a photo of a public sign, entrance, landmark feature, or safely visible detail."
        return PreparedMission(
            text = verifiedMissionText,
            target = verifiedTargetName,
            history = (verifiedTargetName + "|" + history).take(500),
            targetLat = place.latitude,
            targetLng = place.longitude
        )
    }

    private fun buildFallbackMission(
        history: String,
        city: String?,
        lat: Double,
        lng: Double
    ): PreparedMission {
        val area = city?.trim()?.takeIf { it.isNotEmpty() }
        val explorationText = if (area == null) {
            "Explore your nearby area and take a photo of an interesting public landmark, mural, sign, park feature, or street detail."
        } else {
            "Explore a nearby public place in $area and take a photo of an interesting public landmark, mural, sign, park feature, or street detail."
        }
        val missionText = "Could not verify a specific destination, so Wanderly created a nearby exploration mission. $explorationText"
        return PreparedMission(
            text = missionText,
            target = FALLBACK_MISSION_TARGET,
            history = (FALLBACK_MISSION_TARGET + "|" + history).take(500),
            targetLat = lat,
            targetLng = lng
        )
    }

    fun verifyPhoto(bitmap: Bitmap) {
        _missionState.postValue(MissionState.Verifying)

        viewModelScope.launch {
            try {
                val targetName = repository.getMissionTarget() ?: "this place"
                val targetCity = repository.getMissionCity() ?: ""
                val prompt = if (targetName == FALLBACK_MISSION_TARGET) {
                    """
                        Objective Validation System:
                        Target: a nearby public exploration detail in "$targetCity"

                        Does the image show an interesting public landmark, mural, sign, park feature, street detail, or other safely accessible public place detail?
                        Return ONLY raw JSON:
                        {"verified":true,"reason":"The image clearly matches the exploration mission."}

                        Use false when the image is private, unsafe, unrelated, missing, ambiguous, or clearly wrong.
                    """.trimIndent()
                } else {
                    """
                        Objective Validation System:
                        Target: "$targetName" in "$targetCity"

                        Does the image show "$targetName" in "$targetCity"?
                        Return ONLY raw JSON:
                        {"verified":true,"reason":"The image clearly matches the mission."}

                        Use false when the target is missing, ambiguous, or clearly wrong.
                    """.trimIndent()
                }

                val resultText = WanderlyGraph.missionGenerationService()
                    .analyzeImage(bitmap, prompt)
                    .trim()
                val verification = AiResponseParser.parsePhotoVerification(resultText)
                if (verification.verified) {
                    verificationStep = VERIFICATION_STEP_VERIFIED
                    _missionState.postValue(
                        MissionState.VerificationResult(
                            true,
                            UiText.DynamicString("Location verified successfully.")
                        )
                    )
                } else {
                    verificationStep = VERIFICATION_STEP_RECEIVED
                    val message = verification.reason.orEmpty()
                    _missionState.postValue(
                        MissionState.VerificationResult(
                            false,
                            UiText.DynamicString(
                                if (message.isEmpty()) "Could not verify location." else message
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                postGenericMissionError("Mission verification failed", e)
            }
        }
    }

    fun fetchPlaceDetails() {
        _missionState.postValue(MissionState.FetchingDetails)

        viewModelScope.launch {
            try {
                val targetName = repository.getMissionTarget() ?: return@launch
                val targetCity = repository.getMissionCity() ?: ""
                val info = missionDetailsRepository.getPlaceDetails(targetName, targetCity)
                _missionState.postValue(MissionState.DetailsReceived(info))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postGenericMissionError("Mission fetch failed", e)
            }
        }
    }

    fun completeMission(
        distanceKm: Double = 0.0,
        isGroup: Boolean = false,
        isNewLocation: Boolean = false,
        isWild: Boolean = false
    ) {
        if (verificationStep != VERIFICATION_STEP_VERIFIED) {
            _missionState.postValue(
                MissionState.Error(
                    UiText.DynamicString("Take and verify a mission photo before claiming rewards.")
                )
            )
            return
        }
        _missionState.postValue(MissionState.Completing)

        viewModelScope.launch {
            try {
                when (val result = repository.completeMission()) {
                    is MissionCompletionResult.Completed -> {
                        repository.updateLastVisitDate(result.lastMissionDate)
                        repository.clearMissionData()
                        currentMission = null
                        verificationStep = VERIFICATION_STEP_IDLE

                        val refreshedProfile = profileStateProvider.refreshProfile()
                        _profile.postValue(refreshedProfile)
                        _missionState.postValue(MissionState.Idle)

                        val message = StringBuilder()
                        if (result.streakBonusHoney > 0) {
                            message.append(
                                "Streak: ${result.streakCount} days! +${result.streakBonusHoney} Honey bonus!\n"
                            )
                            if (result.streakCount % 5 == 0) {
                                WanderlyNotificationManager.sendMilestoneCelebration(
                                    repository.context,
                                    result.streakCount
                                )
                            }
                        } else if (result.streakCount == 1) {
                            message.append("Streak started! First buzz today!\n")
                        }

                        _streakMessage.postValue(message.toString().trim())
                    }
                    is MissionCompletionResult.AlreadyCompleted -> {
                        repository.clearMissionData()
                        currentMission = null
                        verificationStep = VERIFICATION_STEP_IDLE
                        _missionState.postValue(MissionState.Idle)
                        _streakMessage.postValue("Mission already completed today.")
                    }
                    else -> {
                        _missionState.postValue(MissionState.Error(missionCompletionError(result)))
                    }
                }
            } catch (e: Exception) {
                postGenericMissionError("Mission completion failed", e)
            }
        }
    }

    private fun missionCompletionError(result: MissionCompletionResult): UiText =
        when (result) {
            MissionCompletionResult.Unauthenticated ->
                UiText.DynamicString("Please sign in again to complete missions.")
            MissionCompletionResult.Forbidden ->
                UiText.DynamicString("Mission completion is not allowed for this account.")
            MissionCompletionResult.RateLimited ->
                UiText.DynamicString("Too many mission completion attempts. Try again later.")
            MissionCompletionResult.NetworkFailure,
            MissionCompletionResult.ParseFailure,
            MissionCompletionResult.ServerFailure ->
                UiText.resource(R.string.error_generic_retry)
            is MissionCompletionResult.AlreadyCompleted,
            is MissionCompletionResult.Completed ->
                UiText.resource(R.string.error_generic_retry)
        }

    private fun startProfileCollector() {
        profileCollectorJob?.cancel()
        profileCollectorJob = profileStateProvider.collectProfile(viewModelScope) { profile ->
            _profile.postValue(profile)
        }
    }

    fun currentMissionText(): String? = currentMission

    private fun restoreMissionState(): MissionState {
        val mission = currentMission ?: return MissionState.Idle
        return when (verificationStep) {
            VERIFICATION_STEP_VERIFIED -> MissionState.VerificationResult(
                success = true,
                message = UiText.DynamicString("Location verified successfully.")
            )
            VERIFICATION_STEP_RECEIVED -> MissionState.MissionReceived(mission)
            else -> MissionState.Idle
        }
    }

    private fun postGenericMissionError(message: String, e: Exception) {
        CrashReporter.recordNonFatal(
            CrashEvent.MISSION_FLOW_FAILED,
            e,
            CrashKey.COMPONENT to "missions",
            CrashKey.OPERATION to message.lowercase(Locale.US).replace(' ', '_')
        )
        if (BuildConfig.DEBUG) {
            AppLogger.e("MissionsViewModel", message, e)
        }
        _missionState.postValue(
            MissionState.Error(UiText.resource(R.string.error_generic_retry))
        )
    }

    private fun logFallback(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            AppLogger.w(
                "MissionsViewModel",
                "$message [${throwable.javaClass.simpleName}]"
            )
        }
    }

    private fun logFallback(message: String) {
        if (BuildConfig.DEBUG) {
            AppLogger.w("MissionsViewModel", message)
        }
    }

    private data class PreparedMission(
        val text: String,
        val target: String,
        val history: String,
        val targetLat: Double,
        val targetLng: Double
    )

    companion object {
        internal const val FALLBACK_MISSION_TARGET = "nearby public place"
        private const val DEFAULT_MISSION_TYPE = "landmark"
        private const val KEY_CURRENT_MISSION = "current_mission"
        private const val KEY_VERIFICATION_STEP = "verification_step"
        private const val VERIFICATION_STEP_IDLE = "idle"
        private const val VERIFICATION_STEP_RECEIVED = "received"
        private const val VERIFICATION_STEP_VERIFIED = "verified"
    }
}
