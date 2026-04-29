package com.novahorizon.wanderly.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

open class WanderlyRepository(context: Context) {
    val context: Context = context.applicationContext

    private val preferencesStore = PreferencesStore(this.context)
    private val profileRepository = ProfileRepository(this.context, preferencesStore)
    private val socialRepository = SocialRepository()
    private val discoveryRepository = DiscoveryRepository()
    private val gemCurationRepository = GemCurationRepository(this.context)

    open val currentProfile: StateFlow<Profile?> = profileRepository.currentProfile

    open suspend fun getCurrentProfile(): Profile? = profileRepository.getCurrentProfile()

    open suspend fun updateProfile(profile: Profile): Boolean = profileRepository.updateProfile(profile)

    open suspend fun completeMission(): MissionCompletionResult = profileRepository.completeMission()

    open suspend fun updateProfileLocation(lat: Double, lng: Double): SensitiveProfileMutationResult =
        profileRepository.updateProfileLocation(lat, lng)

    open suspend fun acceptStreakLoss(): SensitiveProfileMutationResult = profileRepository.acceptStreakLoss()

    open suspend fun restoreStreak(cost: Int): SensitiveProfileMutationResult =
        profileRepository.restoreStreak(cost)

    open suspend fun getLeaderboard(): List<Profile> = socialRepository.getLeaderboard()

    open suspend fun addFriendByCode(friendCode: String): String = socialRepository.addFriendByCode(friendCode)

    open suspend fun removeFriend(friendId: String): Boolean = socialRepository.removeFriend(friendId)

    open suspend fun getFriends(): List<Profile> = socialRepository.getFriends()

    open suspend fun fetchNearbyPlaces(lat: Double, lng: Double, radius: Int): List<String> =
        discoveryRepository.fetchNearbyPlaces(lat, lng, radius)

    suspend fun fetchHiddenGems(lat: Double, lng: Double, radius: Int): List<String> =
        discoveryRepository.fetchHiddenGems(lat, lng, radius)

    open suspend fun fetchHiddenGemCandidates(lat: Double, lng: Double, radius: Int, city: String? = null): List<DiscoveredPlace> =
        discoveryRepository.fetchHiddenGemCandidates(lat, lng, radius, city)

    open suspend fun curateHiddenGems(
        city: String,
        candidates: List<DiscoveredPlace>,
        seenGemsHistory: Set<String>
    ): List<Gem> = gemCurationRepository.curateGems(city, candidates, seenGemsHistory)

    suspend fun getCachedUsername(): String? = preferencesStore.getCachedUsername()

    suspend fun cacheUsername(username: String) = preferencesStore.cacheUsername(username)

    suspend fun getLastVisitDate(): String? = preferencesStore.getLastVisitDate()

    suspend fun updateLastVisitDate(date: String) = preferencesStore.updateLastVisitDate(date)

    suspend fun resetMissionDateForTesting(): Boolean = profileRepository.resetMissionDateForTesting()

    open suspend fun getMissionHistory(): String = preferencesStore.getMissionHistory()

    open suspend fun getMissionTarget(): String? = preferencesStore.getMissionTarget()

    open suspend fun getMissionCity(): String? = preferencesStore.getMissionCity()

    suspend fun getMissionText(): String? = preferencesStore.getMissionText()

    suspend fun hasMissionTargetCoordinates(): Boolean = preferencesStore.hasMissionTargetCoordinates()

    suspend fun getMissionTargetCoordinates(): Pair<Double, Double>? = preferencesStore.getMissionTargetCoordinates()

    open suspend fun saveMissionData(
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

    suspend fun clearMissionData() = preferencesStore.clearMissionData()

    suspend fun isRememberMeEnabled(): Boolean = preferencesStore.isRememberMeEnabled()

    suspend fun isRememberMeEnabledSuspend(): Boolean = preferencesStore.isRememberMeEnabledSuspend()

    suspend fun setRememberMeEnabled(enabled: Boolean) = preferencesStore.setRememberMeEnabled(enabled)

    open suspend fun isOnboardingSeen(): Boolean = preferencesStore.isOnboardingSeen()

    open suspend fun isOnboardingSeenSuspend(): Boolean = preferencesStore.isOnboardingSeenSuspend()

    suspend fun setOnboardingSeen(seen: Boolean) = preferencesStore.setOnboardingSeen(seen)

    suspend fun peekPendingInviteCode(): String? = preferencesStore.getPendingInviteCode()

    suspend fun cachePendingInviteCode(code: String) = preferencesStore.setPendingInviteCode(code)

    suspend fun consumePendingInviteCode(): String? = preferencesStore.consumePendingInviteCode()

    suspend fun clearRememberMe() = preferencesStore.setRememberMeEnabled(false)

    suspend fun clearLocalState() = preferencesStore.clearAll()

    open suspend fun uploadAvatar(uri: Uri, profileId: String): String =
        profileRepository.uploadAvatar(uri, profileId)

    fun preferencesStore(): PreferencesStore = preferencesStore
}
