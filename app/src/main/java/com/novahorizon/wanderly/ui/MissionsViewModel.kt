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
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.missions.GeminiMissionResponse
import com.google.ai.client.generativeai.type.content
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
        _missionState.postValue(MissionState.Generating)
        
        viewModelScope.launch {
            try {
                val currentProfile = repository.getCurrentProfile()
                val rank = currentProfile?.hive_rank ?: 1
                val radiusMeters = when (rank) {
                    1 -> 800
                    2 -> 1500
                    3 -> 3000
                    else -> 6000
                }

                val nearbyPlaces = repository.fetchNearbyPlaces(lat, lng, radiusMeters)
                val history = repository.getMissionHistory()
                val promptCity = city ?: "this specific local area"

                val placesSection = if (nearbyPlaces.isNotEmpty()) {
                    "List of real nearby locations in $promptCity:\n${nearbyPlaces.joinToString("\n") { "- $it" }}\nPick one from this list or use Google Search to find a better one."
                } else "Use Google Search to find a real, popular landmark or tourist attraction specifically located within $promptCity."

                val prompt = """
                    You are a professional mission generator for a travel app.
                    Context: User is currently in $promptCity.
                    $placesSection
                    
                    TASK:
                    Generate ONE concrete, realistic travel mission. 
                    The mission must be a simple, physical task that can be verified with a photo.
                    CRITICAL: The location MUST be strictly inside $promptCity. DO NOT suggest locations in other cities.
                    Use your integrated Google Search capability to ensure the place is real and exists in $promptCity.
                    
                    STYLE GUIDE:
                    - "Go to [Location] and take a photo of the main entrance."
                    - "Locate [Location] and take a photo of the sign or plaque."
                    
                    EXCLUSIONS: $history
                    
                    Return ONLY raw JSON: {"missionText": "Concrete instruction here", "targetName": "Exact Location Name"}
                """.trimIndent()

                val responseText = GeminiClient.generateWithSearch(prompt)
                
                val jsonStartIndex = responseText.indexOf("{")
                val jsonEndIndex = responseText.lastIndexOf("}")
                if (jsonStartIndex == -1 || jsonEndIndex == -1) throw Exception("Invalid JSON response")
                
                val finalJson = responseText.substring(jsonStartIndex, jsonEndIndex + 1)
                
                val missionResponse = json.decodeFromString<GeminiMissionResponse>(finalJson)
                val newHistory = (missionResponse.targetName + "|" + history).take(500)
                
                repository.saveMissionData(missionResponse.missionText, missionResponse.targetName, newHistory, city)
                _missionState.postValue(MissionState.MissionReceived(missionResponse.missionText))

            } catch (e: Exception) {
                _missionState.postValue(MissionState.Error("Failed to generate objective: ${e.message}"))
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
                
                val response = GeminiClient.model.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )

                val resultText = response.text?.trim()?.uppercase() ?: ""
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
                
                val info = GeminiClient.generateWithSearch(prompt)
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
