package com.novahorizon.wanderly.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class WanderlyRepository(val context: Context) {
    private val preferencesStore = PreferencesStore(context)
    private val profileRepository = ProfileRepository(context, preferencesStore)
    private val socialRepository = SocialRepository()
    private val discoveryRepository = DiscoveryRepository()

    val currentProfile: StateFlow<Profile?> = profileRepository.currentProfile

    suspend fun getCurrentProfile(): Profile? = profileRepository.getCurrentProfile()

    suspend fun updateProfile(profile: Profile): Boolean = profileRepository.updateProfile(profile)

    suspend fun getLeaderboard(): List<Profile> = socialRepository.getLeaderboard()

    suspend fun addFriendByCode(friendCode: String): String = socialRepository.addFriendByCode(friendCode)

    suspend fun removeFriend(friendId: String): Boolean = socialRepository.removeFriend(friendId)

    suspend fun getFriends(): List<Profile> = socialRepository.getFriends()

    suspend fun fetchNearbyPlaces(lat: Double, lng: Double, radius: Int): List<String> =
        discoveryRepository.fetchNearbyPlaces(lat, lng, radius)

    suspend fun fetchHiddenGems(lat: Double, lng: Double, radius: Int): List<String> =
        discoveryRepository.fetchHiddenGems(lat, lng, radius)

    fun getCachedUsername(): String? = preferencesStore.getCachedUsername()

    fun cacheUsername(username: String) = preferencesStore.cacheUsername(username)

    fun getLastVisitDate(): String? = preferencesStore.getLastVisitDate()

    fun updateLastVisitDate(date: String) = preferencesStore.updateLastVisitDate(date)

    suspend fun resetMissionDateForTesting(): Boolean = profileRepository.resetMissionDateForTesting()

    fun getMissionHistory(): String = preferencesStore.getMissionHistory()

    fun getMissionTarget(): String? = preferencesStore.getMissionTarget()

    fun getMissionCity(): String? = preferencesStore.getMissionCity()

    fun getMissionText(): String? = preferencesStore.getMissionText()

    fun getMissionTargetCoordinates(): Pair<Double, Double>? = preferencesStore.getMissionTargetCoordinates()

    fun saveMissionData(
        text: String,
        target: String,
        history: String,
        city: String?,
        targetLat: Double,
        targetLng: Double
    ) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Calendar.getInstance().time)
        updateLastVisitDate(today)
        preferencesStore.saveMissionData(text, target, history, city, targetLat, targetLng)
    }

    fun clearMissionData() = preferencesStore.clearMissionData()

    fun isRememberMeEnabled(): Boolean = preferencesStore.isRememberMeEnabled()

    fun setRememberMeEnabled(enabled: Boolean) = preferencesStore.setRememberMeEnabled(enabled)

    fun clearLocalState() = preferencesStore.clearAll()

    suspend fun uploadAvatar(uri: Uri, profileId: String): String? =
        profileRepository.uploadAvatar(uri, profileId)
}
