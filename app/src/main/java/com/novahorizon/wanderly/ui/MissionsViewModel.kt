/* FIXES APPLIED: BUG E — see inline comments */
package com.novahorizon.wanderly.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.GeminiClient
import com.novahorizon.wanderly.api.PlacesGeocoder
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.data.derivedHiveRank
import com.novahorizon.wanderly.ui.missions.GeminiMissionResponse
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

class MissionsViewModel(private val repository: WanderlyRepository) : ViewModel() {

    private val _profile = MutableLiveData<Profile?>()
    val profile: LiveData<Profile?> = _profile

    private val _missionState = MutableLiveData<MissionState>(MissionState.Idle)
    val missionState: LiveData<MissionState> = _missionState
    
    private val _streakMessage = MutableLiveData<String?>()
    val streakMessage: LiveData<String?> = _streakMessage

    private val json = Json { ignoreUnknownKeys = true }
    private var missionGenerationInFlight = false

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
        viewModelScope.launch {
            repository.currentProfile.collectLatest {
                _profile.postValue(it)
            }
        }
        viewModelScope.launch {
            repository.getCurrentProfile()
        }
    }

    fun generateMission(lat: Double, lng: Double, city: String?) {
        if (missionGenerationInFlight) {
            Log.w("MissionsViewModel", "Ignoring duplicate mission generation request.")
            return
        }
        missionGenerationInFlight = true
        _missionState.postValue(MissionState.Generating)

        viewModelScope.launch {
            try {
                val currentProfile = repository.getCurrentProfile()
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
                    GeminiClient.generateText(prompt)
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
                    GeminiClient.generateWithSearch(prompt)
                }
                
                val jsonStartIndex = responseText.indexOf("{")
                val jsonEndIndex = responseText.lastIndexOf("}")
                if (jsonStartIndex == -1 || jsonEndIndex == -1) throw Exception("Invalid JSON response")
                
                val finalJson = responseText.substring(jsonStartIndex, jsonEndIndex + 1)
                
                val missionResponse = json.decodeFromString<GeminiMissionResponse>(finalJson)
                val resolvedTarget = PlacesGeocoder.resolveCoordinates(
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
                _missionState.postValue(MissionState.MissionReceived(verifiedMissionText))

            } catch (e: Exception) {
                _missionState.postValue(MissionState.Error("Failed to generate objective: ${e.message}"))
            } finally {
                missionGenerationInFlight = false
            }
        }
    }

    fun verifyPhoto(bitmap: Bitmap) {
        val targetName = repository.getMissionTarget() ?: "this place"
        val targetCity = repository.getMissionCity() ?: ""
        _missionState.postValue(MissionState.Verifying)
        
        viewModelScope.launch {
            try {
                val prompt = """
                    Objective Validation System:
                    Target: "$targetName" in "$targetCity"
                    
                    Does the image show "$targetName" in "$targetCity"?
                    Respond: "YES: [Confirmation]" or "NO: [Reason]".
                """.trimIndent()
                
                val resultText = GeminiClient.analyzeImage(bitmap, prompt).trim().uppercase()
                if (resultText.contains("YES")) {
                    _missionState.postValue(MissionState.VerificationResult(true, "Location verified successfully."))
                } else {
                    val msg = resultText.substringAfter(":").trim().lowercase().replaceFirstChar { it.uppercase() }
                    _missionState.postValue(MissionState.VerificationResult(false, if (msg.isEmpty()) "Could not verify location." else msg))
                }
            } catch (e: Exception) {
                _missionState.postValue(MissionState.Error("System Error: ${e.message}"))
            }
        }
    }

    fun getPlaceDetails() {
        val targetName = repository.getMissionTarget() ?: return
        val targetCity = repository.getMissionCity() ?: ""
        _missionState.postValue(MissionState.FetchingDetails)
        
        viewModelScope.launch {
            try {
                val prompt = """
                    STRICT ACCURACY MODE with Google Search. 
                    Place: "$targetName" in the city of "$targetCity".
                    Task: Provide a 3-sentence summary using real, up-to-date information.
                    CRITICAL: Use Google Search to verify details about "$targetName" in "$targetCity".
                    DO NOT mention venues from other cities.
                    Include one unique fun fact discovered via search.
                """.trimIndent()
                
                val info = GeminiClient.generateWithSearchText(
                    prompt,
                    systemInstruction = "You are a precise local travel guide. Return normal plain text only. Do not return JSON, markdown, bullet lists, or code fences."
                )
                _missionState.postValue(MissionState.DetailsReceived(info))
            } catch (e: Exception) {
                _missionState.postValue(MissionState.Error("Could not fetch details: ${e.message}"))
            }
        }
    }

    fun completeMission(distanceKm: Double = 0.0, isGroup: Boolean = false, isNewLocation: Boolean = false, isWild: Boolean = false) {
        val current = _profile.value ?: return
        _missionState.postValue(MissionState.Completing)
        
        viewModelScope.launch {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                val today = sdf.format(now.time)
                
                val yesterdayCal = now.clone() as Calendar
                yesterdayCal.add(Calendar.DAY_OF_YEAR, -1)
                val yesterday = sdf.format(yesterdayCal.time)
                
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
                } else {
                    // Already mission today. We still allow finishing, but don't double increment streak.
                }

                val newHoney = (current.honey ?: 0) + Constants.MISSION_HONEY_REWARD + streakBonusHoney
                
                val updatedProfile = current.copy(
                    honey = newHoney,
                    last_mission_date = today,
                    streak_count = newStreak
                )
                
                val success = repository.updateProfile(updatedProfile)
                if (success) {
                    // BUG E FIXED: Use UTC for consistent date recording
                    val sdfLocal = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { 
                        timeZone = TimeZone.getTimeZone("UTC") 
                    }
                    val todayUTC = sdfLocal.format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).time)
                    repository.updateLastVisitDate(todayUTC)
                    repository.clearMissionData()

                    _profile.postValue(updatedProfile)
                    _missionState.postValue(MissionState.Idle)
                    
                    val msg = StringBuilder()
                    if (streakBonusHoney > 0) {
                        msg.append("Streak: $newStreak Days! 🔥 +$streakBonusHoney Honey Bonus!\n")
                        if (newStreak % 5 == 0) {
                            WanderlyNotificationManager.sendMilestoneCelebration(repository.context, newStreak)
                        }
                    } else if (lastMissionDate != today) {
                        msg.append("Streak Started! 🐝 First buzz today!\n")
                    }
                    
                    _streakMessage.postValue(msg.toString().trim())
                } else {
                    _missionState.postValue(MissionState.Error("Failed to save progress to the hive. Check your connection!"))
                }
            } catch (e: Exception) {
                Log.e("WanderlyStreak", "Error calculating streak", e)
                _missionState.postValue(MissionState.Error("Error: ${e.message}"))
            }
        }
    }
}
