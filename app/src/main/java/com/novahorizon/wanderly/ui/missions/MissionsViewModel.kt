package com.novahorizon.wanderly.ui.missions

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.api.PlacesGeocoder
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.MissionDetailsRepository
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.data.derivedHiveRank
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.util.AiResponseParser
import com.novahorizon.wanderly.util.DateUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MissionsViewModel(
    private val repository: WanderlyRepository,
    private val savedStateHandle: SavedStateHandle,
    private val profileStateProvider: ProfileStateProvider,
    private val missionDetailsRepository: MissionDetailsRepository
) : ViewModel() {

    private val _profile = MutableLiveData<Profile?>()
    val profile: LiveData<Profile?> = _profile

    private val _missionState = MutableLiveData<MissionState>(restoreMissionState())
    val missionState: LiveData<MissionState> = _missionState

    private val _streakMessage = MutableLiveData<String?>()
    val streakMessage: LiveData<String?> = _streakMessage

    private val json = Json { ignoreUnknownKeys = true }
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
        data class VerificationResult(val success: Boolean, val message: String) : MissionState()
        object Completing : MissionState()
        data class Error(val message: String) : MissionState()
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
                Log.w("MissionsViewModel", "Ignoring duplicate mission generation request.")
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

                val nearbyPlaces = withTimeoutOrNull(1_500L) {
                    repository.fetchNearbyPlaces(lat, lng, radiusMeters)
                }.orEmpty()
                val history = repository.getMissionHistory()
                val promptCity = city ?: "this specific local area"
                val recentExclusions = history.ifBlank { "none" }

                val responseText = if (nearbyPlaces.isNotEmpty()) {
                    val candidateList = nearbyPlaces.take(5).joinToString("\n") { "- $it" }
                    val prompt = """
                        User location: $promptCity
                        Choose ONE exact place name from this list and turn it into a simple photo mission:
                        $candidateList

                        Rules:
                        - Keep the place inside $promptCity.
                        - Use the exact place name from the list.
                        - Make it easy to verify with one photo.
                        - Exclude hotels, clinics, pharmacies, schools, banks, offices, and apartment blocks.
                        - Avoid anything already used: $recentExclusions

                        Return ONLY raw JSON:
                        {"missionText":"Go to [Place] and take a photo of the sign or entrance.","targetName":"Exact Place Name"}
                    """.trimIndent()
                    WanderlyGraph.missionGenerationService().generateText(prompt)
                } else {
                    val prompt = """
                        User location: $promptCity
                        Find ONE real public place strictly inside $promptCity and turn it into a simple photo mission.

                        Rules:
                        - Use the exact current Google Maps place name.
                        - It must be searchable right now.
                        - Exclude hotels, clinics, pharmacies, schools, government buildings, banks, offices, and apartment blocks.
                        - Avoid anything already used: $recentExclusions

                        Return ONLY raw JSON:
                        {"missionText":"Go to [Place] and take a photo of the sign or entrance.","targetName":"Exact Place Name"}
                    """.trimIndent()
                    WanderlyGraph.missionGenerationService().generateWithSearch(prompt)
                }

                val finalJson = AiResponseParser.extractFirstJsonObject(responseText)
                    ?: throw IllegalStateException("Buzzy couldn't understand the mission response. Please try again.")
                val missionResponse = runCatching {
                    json.decodeFromString<GeminiMissionResponse>(finalJson)
                }.onFailure {
                    logRawResponse("mission", responseText)
                }.getOrElse {
                    throw IllegalStateException("Buzzy couldn't understand the mission response. Please try again.")
                }
                val resolvedTarget = WanderlyGraph.missionGenerationService().resolveCoordinates(
                    placeName = missionResponse.targetName,
                    targetCity = city.orEmpty(),
                    userLat = lat,
                    userLng = lng,
                    radiusKm = radiusMeters / 1000.0
                ) ?: throw Exception("Could not verify the mission destination. Please try again.")

                val verifiedTargetName = resolvedTarget.name
                val verifiedMissionText = missionResponse.missionText.replace(
                    missionResponse.targetName,
                    verifiedTargetName
                )
                val newHistory = (verifiedTargetName + "|" + history).take(500)

                repository.saveMissionData(
                    text = verifiedMissionText,
                    target = verifiedTargetName,
                    history = newHistory,
                    city = city,
                    targetLat = resolvedTarget.lat,
                    targetLng = resolvedTarget.lng
                )
                currentMission = verifiedMissionText
                verificationStep = VERIFICATION_STEP_RECEIVED
                _missionState.postValue(MissionState.MissionReceived(verifiedMissionText))
            } catch (e: Exception) {
                postGenericMissionError("Mission generation failed", e)
            } finally {
                missionGenerationInFlight = false
            }
        }
    }

    fun verifyPhoto(bitmap: Bitmap) {
        _missionState.postValue(MissionState.Verifying)

        viewModelScope.launch {
            try {
                val targetName = repository.getMissionTarget() ?: "this place"
                val targetCity = repository.getMissionCity() ?: ""
                val prompt = """
                    Objective Validation System:
                    Target: "$targetName" in "$targetCity"

                    Does the image show "$targetName" in "$targetCity"?
                    Respond: "YES: [Confirmation]" or "NO: [Reason]".
                """.trimIndent()

                val resultText = WanderlyGraph.missionGenerationService()
                    .analyzeImage(bitmap, prompt)
                    .trim()
                    .uppercase()
                if (resultText.contains("YES")) {
                    verificationStep = VERIFICATION_STEP_VERIFIED
                    _missionState.postValue(
                        MissionState.VerificationResult(true, "Location verified successfully.")
                    )
                } else {
                    verificationStep = VERIFICATION_STEP_RECEIVED
                    val message = resultText.substringAfter(":")
                        .trim()
                        .lowercase()
                        .replaceFirstChar { it.uppercase() }
                    _missionState.postValue(
                        MissionState.VerificationResult(
                            false,
                            if (message.isEmpty()) "Could not verify location." else message
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
        val current = _profile.value ?: return
        _missionState.postValue(MissionState.Completing)

        viewModelScope.launch {
            try {
                val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                val today = DateUtils.formatUtcDate(now.time)

                val yesterdayCal = now.clone() as Calendar
                yesterdayCal.add(Calendar.DAY_OF_YEAR, -1)
                val yesterday = DateUtils.formatUtcDate(yesterdayCal.time)

                val lastMissionDate = current.last_mission_date ?: ""
                var newStreak = current.streak_count ?: 0
                var streakBonusHoney = 0

                if (lastMissionDate != today) {
                    if (lastMissionDate == yesterday) {
                        newStreak += 1
                        streakBonusHoney = 10 + (newStreak / 5) * 5
                    } else {
                        newStreak = 1
                    }
                }

                val newHoney = (current.honey ?: 0) + Constants.MISSION_HONEY_REWARD + streakBonusHoney
                val updatedProfile = current.copy(
                    honey = newHoney,
                    last_mission_date = today,
                    streak_count = newStreak
                )

                val success = repository.updateProfile(updatedProfile)
                if (success) {
                    val recordedToday = DateUtils.formatUtcDate(
                        Calendar.getInstance(TimeZone.getTimeZone("UTC")).time
                    )
                    repository.updateLastVisitDate(recordedToday)
                    repository.clearMissionData()
                    currentMission = null
                    verificationStep = VERIFICATION_STEP_IDLE

                    _profile.postValue(updatedProfile)
                    _missionState.postValue(MissionState.Idle)

                    val message = StringBuilder()
                    if (streakBonusHoney > 0) {
                        message.append("Streak: $newStreak days! +$streakBonusHoney Honey bonus!\n")
                        if (newStreak % 5 == 0) {
                            WanderlyNotificationManager.sendMilestoneCelebration(
                                repository.context,
                                newStreak
                            )
                        }
                    } else if (lastMissionDate != today) {
                        message.append("Streak started! First buzz today!\n")
                    }

                    _streakMessage.postValue(message.toString().trim())
                } else {
                    _missionState.postValue(
                        MissionState.Error("Failed to save progress to the hive. Check your connection!")
                    )
                }
            } catch (e: Exception) {
                postGenericMissionError("Mission completion failed", e)
            }
        }
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
                message = "Location verified successfully."
            )
            VERIFICATION_STEP_RECEIVED -> MissionState.MissionReceived(mission)
            else -> MissionState.Idle
        }
    }

    private fun logRawResponse(label: String, response: String) {
        if (BuildConfig.DEBUG) {
            Log.d("MissionsViewModel", "Raw $label response: $response")
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
            Log.e("MissionsViewModel", message, e)
        }
        _missionState.postValue(
            MissionState.Error(repository.context.getString(R.string.error_generic_retry))
        )
    }

    private companion object {
        const val KEY_CURRENT_MISSION = "current_mission"
        const val KEY_VERIFICATION_STEP = "verification_step"
        const val VERIFICATION_STEP_IDLE = "idle"
        const val VERIFICATION_STEP_RECEIVED = "received"
        const val VERIFICATION_STEP_VERIFIED = "verified"
    }
}
