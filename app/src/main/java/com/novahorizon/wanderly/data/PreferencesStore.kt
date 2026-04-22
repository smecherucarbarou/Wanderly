package com.novahorizon.wanderly.data

import android.content.Context
import com.novahorizon.wanderly.Constants
import kotlinx.coroutines.runBlocking

class PreferencesStore(context: Context) {
    private val dataStoreManager = DataStoreManager(context.applicationContext)

    fun isRememberMeEnabled(): Boolean = blockingRead {
        dataStoreManager.getMainBoolean(Constants.KEY_REMEMBER_ME, false)
    }

    fun setRememberMeEnabled(enabled: Boolean) = blockingWrite {
        dataStoreManager.putMainBoolean(Constants.KEY_REMEMBER_ME, enabled)
    }

    fun isOnboardingSeen(): Boolean = blockingRead {
        dataStoreManager.getMainBoolean(Constants.KEY_ONBOARDING_SEEN, false)
    }

    fun setOnboardingSeen(seen: Boolean) = blockingWrite {
        dataStoreManager.putMainBoolean(Constants.KEY_ONBOARDING_SEEN, seen)
    }

    fun getPendingInviteCode(): String? = blockingRead {
        dataStoreManager.getMainString(Constants.KEY_PENDING_INVITE_CODE, null)
    }

    fun setPendingInviteCode(code: String) = blockingWrite {
        dataStoreManager.putMainString(Constants.KEY_PENDING_INVITE_CODE, code)
    }

    fun clearPendingInviteCode() = blockingWrite {
        dataStoreManager.removeMainKeys(listOf(Constants.KEY_PENDING_INVITE_CODE))
    }

    fun consumePendingInviteCode(): String? {
        val code = getPendingInviteCode() ?: return null
        clearPendingInviteCode()
        return code
    }

    fun clearAll() = blockingWrite {
        dataStoreManager.clearMainStore()
    }

    fun getCachedUsername(): String? = blockingRead {
        dataStoreManager.getMainString(Constants.KEY_USERNAME, null)
    }

    fun cacheUsername(username: String) = blockingWrite {
        dataStoreManager.putMainString(Constants.KEY_USERNAME, username)
    }

    fun getLastVisitDate(): String? = blockingRead {
        dataStoreManager.getMainString(Constants.KEY_LAST_VISIT, "")
    }

    fun updateLastVisitDate(date: String) = blockingWrite {
        dataStoreManager.putMainString(Constants.KEY_LAST_VISIT, date)
    }

    fun getMissionHistory(): String = blockingRead {
        dataStoreManager.getMainString(Constants.KEY_MISSION_HISTORY, "") ?: ""
    }

    fun getMissionTarget(): String? = blockingRead {
        dataStoreManager.getMainString(Constants.KEY_MISSION_TARGET, null)
    }

    fun getMissionCity(): String? = blockingRead {
        dataStoreManager.getMainString(Constants.KEY_MISSION_CITY, null)
    }

    fun getMissionText(): String? = blockingRead {
        dataStoreManager.getMainString(Constants.KEY_MISSION_TEXT, null)
    }

    fun hasMissionTargetCoordinates(): Boolean = getMissionTargetCoordinates() != null

    fun getMissionTargetCoordinates(): Pair<Double, Double>? = blockingRead {
        val lat = getMissionCoordinateAsync(
            typedKey = Constants.KEY_MISSION_TARGET_LAT_TYPED,
            legacyKey = Constants.KEY_MISSION_TARGET_LAT
        )
        val lng = getMissionCoordinateAsync(
            typedKey = Constants.KEY_MISSION_TARGET_LNG_TYPED,
            legacyKey = Constants.KEY_MISSION_TARGET_LNG
        )
        if (lat != null && lng != null) lat to lng else null
    }

    fun saveMissionData(
        text: String,
        target: String,
        history: String,
        city: String?,
        targetLat: Double,
        targetLng: Double
    ) = blockingWrite {
        dataStoreManager.putMainString(Constants.KEY_MISSION_TEXT, text)
        dataStoreManager.putMainString(Constants.KEY_MISSION_TARGET, target)
        dataStoreManager.putMainString(Constants.KEY_MISSION_CITY, city)
        dataStoreManager.putMainString(Constants.KEY_MISSION_HISTORY, history)
        dataStoreManager.putMainFloat(Constants.KEY_MISSION_TARGET_LAT_TYPED, targetLat.toFloat())
        dataStoreManager.putMainFloat(Constants.KEY_MISSION_TARGET_LNG_TYPED, targetLng.toFloat())
        dataStoreManager.removeMainKeys(
            listOf(Constants.KEY_MISSION_TARGET_LAT, Constants.KEY_MISSION_TARGET_LNG)
        )
    }

    fun clearMissionData() = blockingWrite {
        dataStoreManager.removeMainKeys(
            listOf(
                Constants.KEY_MISSION_TEXT,
                Constants.KEY_MISSION_TARGET,
                Constants.KEY_MISSION_CITY,
                Constants.KEY_MISSION_TARGET_LAT,
                Constants.KEY_MISSION_TARGET_LNG,
                Constants.KEY_MISSION_TARGET_LAT_TYPED,
                Constants.KEY_MISSION_TARGET_LNG_TYPED
            )
        )
    }

    fun getNotificationCooldown(key: String): Long = blockingRead {
        dataStoreManager.getNotificationCooldown(key)
    }

    fun setNotificationCooldown(key: String, timestamp: Long) = blockingWrite {
        dataStoreManager.setNotificationCooldown(key, timestamp)
    }

    fun clearNotificationCooldowns() = blockingWrite {
        dataStoreManager.clearNotificationCooldowns()
    }

    fun getNotificationCooldownKeys(): Set<String> = blockingRead {
        dataStoreManager.getNotificationCooldownKeys()
    }

    fun removeNotificationCooldown(key: String) = blockingWrite {
        dataStoreManager.removeNotificationCooldown(key)
    }

    fun getNotificationCheckString(key: String): String? = blockingRead {
        dataStoreManager.getNotificationCheckString(key)
    }

    fun putNotificationCheckString(key: String, value: String) = blockingWrite {
        dataStoreManager.putNotificationCheckString(key, value)
    }

    fun getNotificationCheckBoolean(key: String): Boolean = blockingRead {
        dataStoreManager.getNotificationCheckBoolean(key)
    }

    fun putNotificationCheckBoolean(key: String, value: Boolean) = blockingWrite {
        dataStoreManager.putNotificationCheckBoolean(key, value)
    }

    fun removeNotificationCheckKey(key: String) = blockingWrite {
        dataStoreManager.removeNotificationCheckKeys(listOf(key))
    }

    fun removeNotificationCheckKeys(keys: Iterable<String>) = blockingWrite {
        dataStoreManager.removeNotificationCheckKeys(keys)
    }

    fun clearNotificationCheckState() = blockingWrite {
        dataStoreManager.clearNotificationCheckState()
    }

    fun getNotificationCheckKeys(): Set<String> = blockingRead {
        dataStoreManager.getNotificationCheckKeys()
    }

    suspend fun cacheProfileStreakState(lastMissionDate: String?, streakCount: Int?) {
        dataStoreManager.putMainString(Constants.KEY_LOCAL_LAST_MISSION_DATE, lastMissionDate)
        dataStoreManager.putMainInt(Constants.KEY_LOCAL_STREAK_COUNT, streakCount ?: 0)
    }

    suspend fun getStoredLastMissionDate(): String? =
        dataStoreManager.getMainString(Constants.KEY_LOCAL_LAST_MISSION_DATE, null)

    suspend fun getStoredStreakCount(): Int =
        dataStoreManager.getMainInt(Constants.KEY_LOCAL_STREAK_COUNT, 0)

    suspend fun saveWidgetStreakSnapshot(snapshot: WidgetStreakSnapshot) {
        dataStoreManager.putMainInt(Constants.KEY_WIDGET_STREAK_COUNT, snapshot.streakCount)
        dataStoreManager.putMainString(Constants.KEY_WIDGET_LAST_MISSION_DATE, snapshot.lastMissionDate)
        dataStoreManager.putMainLong(Constants.KEY_WIDGET_STREAK_SAVED_AT_MILLIS, snapshot.savedAtMillis)
        dataStoreManager.putMainBoolean(Constants.KEY_WIDGET_LAST_SYNC_SUCCEEDED, snapshot.lastSyncSucceeded)
    }

    suspend fun getWidgetStreakSnapshot(): WidgetStreakSnapshot? {
        val savedAtMillis = dataStoreManager.getMainLong(
            Constants.KEY_WIDGET_STREAK_SAVED_AT_MILLIS,
            default = -1L
        )
        if (savedAtMillis < 0L) return null

        return WidgetStreakSnapshot(
            streakCount = dataStoreManager.getMainInt(Constants.KEY_WIDGET_STREAK_COUNT, 0),
            lastMissionDate = dataStoreManager.getMainString(Constants.KEY_WIDGET_LAST_MISSION_DATE, null),
            savedAtMillis = savedAtMillis,
            lastSyncSucceeded = dataStoreManager.getMainBoolean(
                Constants.KEY_WIDGET_LAST_SYNC_SUCCEEDED,
                false
            )
        )
    }

    private suspend fun getMissionCoordinateAsync(typedKey: String, legacyKey: String): Double? {
        dataStoreManager.getMainFloat(typedKey)?.let { return it.toDouble() }

        val legacyRawValue = dataStoreManager.getMainString(legacyKey, null) ?: return null
        val legacyValue = legacyRawValue.toDoubleOrNull() ?: 0.0
        dataStoreManager.putMainFloat(typedKey, legacyValue.toFloat())
        dataStoreManager.removeMainKeys(listOf(legacyKey))
        return legacyValue
    }

    private fun <T> blockingRead(block: suspend () -> T): T {
        // TODO: Replace remaining synchronous preference callers so DataStore reads stay fully non-blocking.
        return runBlocking { block() }
    }

    private fun blockingWrite(block: suspend () -> Unit) {
        // TODO: Replace remaining synchronous preference callers so DataStore writes stay fully non-blocking.
        runBlocking { block() }
    }

    companion object {
        internal const val NOTIFICATION_CHECK_PREFS_NAME = "notification_check_state"
        internal const val NOTIFICATION_COOLDOWN_PREFS_NAME = "notif_dedup"
    }
}
