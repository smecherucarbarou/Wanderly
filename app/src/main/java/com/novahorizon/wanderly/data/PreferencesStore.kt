package com.novahorizon.wanderly.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.novahorizon.wanderly.Constants

class PreferencesStore(context: Context) {
    private val dataStoreManager = DataStoreManager(context.applicationContext)

    suspend fun isRememberMeEnabled(): Boolean =
        dataStoreManager.getMainBoolean(Constants.KEY_REMEMBER_ME, false)

    suspend fun isRememberMeEnabledSuspend(): Boolean = isRememberMeEnabled()

    suspend fun setRememberMeEnabled(enabled: Boolean) {
        dataStoreManager.putMainBoolean(Constants.KEY_REMEMBER_ME, enabled)
    }

    suspend fun isOnboardingSeen(): Boolean =
        dataStoreManager.getMainBoolean(Constants.KEY_ONBOARDING_SEEN, false)

    suspend fun isOnboardingSeenSuspend(): Boolean = isOnboardingSeen()

    suspend fun setOnboardingSeen(seen: Boolean) {
        dataStoreManager.putMainBoolean(Constants.KEY_ONBOARDING_SEEN, seen)
    }

    suspend fun getPendingInviteCode(): String? =
        dataStoreManager.getMainString(Constants.KEY_PENDING_INVITE_CODE, null)

    suspend fun setPendingInviteCode(code: String) {
        dataStoreManager.putMainString(Constants.KEY_PENDING_INVITE_CODE, code)
    }

    suspend fun clearPendingInviteCode() {
        dataStoreManager.removeMainKeys(listOf(Constants.KEY_PENDING_INVITE_CODE))
    }

    suspend fun consumePendingInviteCode(): String? {
        val code = getPendingInviteCode() ?: return null
        clearPendingInviteCode()
        return code
    }

    suspend fun clearAll() {
        dataStoreManager.clearMainStore()
    }

    suspend fun getCachedUsername(): String? =
        dataStoreManager.getMainString(Constants.KEY_USERNAME, null)

    suspend fun cacheUsername(username: String) {
        dataStoreManager.putMainString(Constants.KEY_USERNAME, username)
    }

    suspend fun getLastVisitDate(): String? =
        dataStoreManager.getMainString(Constants.KEY_LAST_VISIT, "")

    suspend fun updateLastVisitDate(date: String) {
        dataStoreManager.putMainString(Constants.KEY_LAST_VISIT, date)
    }

    suspend fun getMissionHistory(): String =
        dataStoreManager.getMainString(Constants.KEY_MISSION_HISTORY, "") ?: ""

    suspend fun getMissionTarget(): String? =
        dataStoreManager.getMainString(Constants.KEY_MISSION_TARGET, null)

    suspend fun getMissionCity(): String? =
        dataStoreManager.getMainString(Constants.KEY_MISSION_CITY, null)

    suspend fun getMissionText(): String? =
        dataStoreManager.getMainString(Constants.KEY_MISSION_TEXT, null)

    suspend fun hasMissionTargetCoordinates(): Boolean = getMissionTargetCoordinates() != null

    suspend fun getMissionTargetCoordinates(): Pair<Double, Double>? {
        val lat = getMissionCoordinateAsync(
            typedKey = Constants.KEY_MISSION_TARGET_LAT_TYPED,
            legacyKey = Constants.KEY_MISSION_TARGET_LAT
        )
        val lng = getMissionCoordinateAsync(
            typedKey = Constants.KEY_MISSION_TARGET_LNG_TYPED,
            legacyKey = Constants.KEY_MISSION_TARGET_LNG
        )
        return if (lat != null && lng != null) lat to lng else null
    }

    suspend fun saveMissionData(
        text: String,
        target: String,
        history: String,
        city: String?,
        targetLat: Double,
        targetLng: Double
    ) {
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

    suspend fun clearMissionData() {
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

    suspend fun getNotificationCooldown(key: String): Long =
        dataStoreManager.getNotificationCooldown(key)

    suspend fun setNotificationCooldown(key: String, timestamp: Long) {
        dataStoreManager.setNotificationCooldown(key, timestamp)
    }

    suspend fun clearNotificationCooldowns() {
        dataStoreManager.clearNotificationCooldowns()
    }

    suspend fun getNotificationCooldownKeys(): Set<String> =
        dataStoreManager.getNotificationCooldownKeys()

    suspend fun removeNotificationCooldown(key: String) {
        dataStoreManager.removeNotificationCooldown(key)
    }

    suspend fun pruneStaleCooldowns(maxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        dataStoreManager.getNotificationCooldownKeys().forEach { key ->
            val lastSent = dataStoreManager.getNotificationCooldown(key)
            if (lastSent <= 0L || now - lastSent > maxAgeMs) {
                dataStoreManager.removeNotificationCooldown(key)
            }
        }
    }

    suspend fun getNotificationCheckString(key: String): String? =
        dataStoreManager.getNotificationCheckString(key)

    suspend fun putNotificationCheckString(key: String, value: String) {
        dataStoreManager.putNotificationCheckString(key, value)
    }

    suspend fun getNotificationCheckBoolean(key: String): Boolean =
        dataStoreManager.getNotificationCheckBoolean(key)

    suspend fun putNotificationCheckBoolean(key: String, value: Boolean) {
        dataStoreManager.putNotificationCheckBoolean(key, value)
    }

    suspend fun removeNotificationCheckKey(key: String) {
        dataStoreManager.removeNotificationCheckKeys(listOf(key))
    }

    suspend fun removeNotificationCheckKeys(keys: Iterable<String>) {
        dataStoreManager.removeNotificationCheckKeys(keys)
    }

    suspend fun clearNotificationCheckState() {
        dataStoreManager.clearNotificationCheckState()
    }

    suspend fun getNotificationCheckKeys(): Set<String> =
        dataStoreManager.getNotificationCheckKeys()

    suspend fun cacheProfileStreakState(lastMissionDate: String?, streakCount: Int?) {
        dataStoreManager.putMainString(Constants.KEY_LOCAL_LAST_MISSION_DATE, lastMissionDate)
        dataStoreManager.putMainInt(Constants.KEY_LOCAL_STREAK_COUNT, streakCount ?: 0)
    }

    suspend fun getStoredLastMissionDate(): String? =
        dataStoreManager.getMainString(Constants.KEY_LOCAL_LAST_MISSION_DATE, null)

    suspend fun getStoredStreakCount(): Int =
        dataStoreManager.getMainInt(Constants.KEY_LOCAL_STREAK_COUNT, 0)

    suspend fun saveWidgetStreakSnapshot(snapshot: WidgetStreakSnapshot) {
        dataStoreManager.editMain {
            this[intPreferencesKey(Constants.KEY_WIDGET_STREAK_COUNT)] = snapshot.streakCount
            this[longPreferencesKey(Constants.KEY_WIDGET_STREAK_SAVED_AT_MILLIS)] =
                snapshot.savedAtMillis
            this[booleanPreferencesKey(Constants.KEY_WIDGET_LAST_SYNC_SUCCEEDED)] =
                snapshot.lastSyncSucceeded

            val missionDateKey = stringPreferencesKey(Constants.KEY_WIDGET_LAST_MISSION_DATE)
            if (snapshot.lastMissionDate == null) {
                remove(missionDateKey)
            } else {
                this[missionDateKey] = snapshot.lastMissionDate
            }
        }
    }

    suspend fun getWidgetStreakSnapshot(): WidgetStreakSnapshot? {
        val savedAtMillis = dataStoreManager.getMainLong(
            Constants.KEY_WIDGET_STREAK_SAVED_AT_MILLIS,
            default = -1L
        )
        if (savedAtMillis < 0L) return null

        return WidgetStreakSnapshot(
            streakCount = dataStoreManager.getMainInt(Constants.KEY_WIDGET_STREAK_COUNT, 0),
            lastMissionDate = dataStoreManager.getMainString(
                Constants.KEY_WIDGET_LAST_MISSION_DATE,
                null
            ),
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
        dataStoreManager.removeMainKeys(listOf(legacyKey))
        val legacyValue = parseLegacyMissionCoordinate(legacyRawValue) ?: return null
        dataStoreManager.putMainFloat(typedKey, legacyValue.toFloat())
        return legacyValue
    }

    companion object {
        internal const val NOTIFICATION_CHECK_PREFS_NAME = "notification_check_state"
        internal const val NOTIFICATION_COOLDOWN_PREFS_NAME = "notif_dedup"

        internal fun parseLegacyMissionCoordinate(rawValue: String?): Double? {
            return rawValue
                ?.trim()
                ?.toDoubleOrNull()
                ?.takeIf { it in -180.0..180.0 }
        }
    }
}
