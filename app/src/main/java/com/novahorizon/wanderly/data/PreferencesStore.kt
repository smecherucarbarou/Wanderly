package com.novahorizon.wanderly.data

import android.content.Context
import android.content.SharedPreferences
import com.novahorizon.wanderly.Constants

class PreferencesStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    private val notificationCooldownPrefs: SharedPreferences =
        appContext.getSharedPreferences(NOTIFICATION_COOLDOWN_PREFS_NAME, Context.MODE_PRIVATE)
    private val notificationCheckPrefs: SharedPreferences =
        appContext.getSharedPreferences(NOTIFICATION_CHECK_PREFS_NAME, Context.MODE_PRIVATE)

    fun isRememberMeEnabled(): Boolean = prefs.getBoolean(Constants.KEY_REMEMBER_ME, false)

    fun setRememberMeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.KEY_REMEMBER_ME, enabled).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun getCachedUsername(): String? = prefs.getString(Constants.KEY_USERNAME, null)

    fun cacheUsername(username: String) {
        prefs.edit().putString(Constants.KEY_USERNAME, username).apply()
    }

    fun getLastVisitDate(): String? = prefs.getString(Constants.KEY_LAST_VISIT, "")

    fun updateLastVisitDate(date: String) {
        prefs.edit().putString(Constants.KEY_LAST_VISIT, date).apply()
    }

    fun getMissionHistory(): String = prefs.getString(Constants.KEY_MISSION_HISTORY, "") ?: ""

    fun getMissionTarget(): String? = prefs.getString(Constants.KEY_MISSION_TARGET, null)

    fun getMissionCity(): String? = prefs.getString(Constants.KEY_MISSION_CITY, null)

    fun getMissionText(): String? = prefs.getString(Constants.KEY_MISSION_TEXT, null)

    fun hasMissionTargetCoordinates(): Boolean = getMissionTargetCoordinates() != null

    fun getMissionTargetCoordinates(): Pair<Double, Double>? {
        val lat = getMissionCoordinate(
            typedKey = Constants.KEY_MISSION_TARGET_LAT_TYPED,
            legacyKey = Constants.KEY_MISSION_TARGET_LAT
        )
        val lng = getMissionCoordinate(
            typedKey = Constants.KEY_MISSION_TARGET_LNG_TYPED,
            legacyKey = Constants.KEY_MISSION_TARGET_LNG
        )
        return if (lat != null && lng != null) lat to lng else null
    }

    fun saveMissionData(
        text: String,
        target: String,
        history: String,
        city: String?,
        targetLat: Double,
        targetLng: Double
    ) {
        prefs.edit()
            .putString(Constants.KEY_MISSION_TEXT, text)
            .putString(Constants.KEY_MISSION_TARGET, target)
            .putString(Constants.KEY_MISSION_CITY, city)
            .putString(Constants.KEY_MISSION_HISTORY, history)
            .putFloat(Constants.KEY_MISSION_TARGET_LAT_TYPED, targetLat.toFloat())
            .putFloat(Constants.KEY_MISSION_TARGET_LNG_TYPED, targetLng.toFloat())
            .remove(Constants.KEY_MISSION_TARGET_LAT)
            .remove(Constants.KEY_MISSION_TARGET_LNG)
            .apply()
    }

    fun clearMissionData() {
        prefs.edit()
            .remove(Constants.KEY_MISSION_TEXT)
            .remove(Constants.KEY_MISSION_TARGET)
            .remove(Constants.KEY_MISSION_CITY)
            .remove(Constants.KEY_MISSION_TARGET_LAT)
            .remove(Constants.KEY_MISSION_TARGET_LNG)
            .remove(Constants.KEY_MISSION_TARGET_LAT_TYPED)
            .remove(Constants.KEY_MISSION_TARGET_LNG_TYPED)
            .apply()
    }

    fun getNotificationCooldown(key: String): Long = notificationCooldownPrefs.getLong(key, 0L)

    fun setNotificationCooldown(key: String, timestamp: Long) {
        notificationCooldownPrefs.edit().putLong(key, timestamp).apply()
    }

    fun clearNotificationCooldowns() {
        notificationCooldownPrefs.edit().clear().apply()
    }

    fun getNotificationCooldownKeys(): Set<String> = notificationCooldownPrefs.all.keys

    fun removeNotificationCooldown(key: String) {
        notificationCooldownPrefs.edit().remove(key).apply()
    }

    fun getNotificationCheckString(key: String): String? =
        notificationCheckPrefs.getString(key, null)

    fun putNotificationCheckString(key: String, value: String) {
        notificationCheckPrefs.edit().putString(key, value).apply()
    }

    fun getNotificationCheckBoolean(key: String): Boolean =
        notificationCheckPrefs.getBoolean(key, false)

    fun putNotificationCheckBoolean(key: String, value: Boolean) {
        notificationCheckPrefs.edit().putBoolean(key, value).apply()
    }

    fun removeNotificationCheckKey(key: String) {
        notificationCheckPrefs.edit().remove(key).apply()
    }

    fun removeNotificationCheckKeys(keys: Iterable<String>) {
        val editor = notificationCheckPrefs.edit()
        keys.forEach(editor::remove)
        editor.apply()
    }

    fun clearNotificationCheckState() {
        notificationCheckPrefs.edit().clear().apply()
    }

    fun getNotificationCheckKeys(): Set<String> = notificationCheckPrefs.all.keys

    private fun getMissionCoordinate(typedKey: String, legacyKey: String): Double? {
        if (prefs.contains(typedKey)) {
            return prefs.getFloat(typedKey, 0f).toDouble()
        }

        val legacyRawValue = prefs.getString(legacyKey, null) ?: return null
        val legacyValue = legacyRawValue.toDoubleOrNull() ?: 0.0
        prefs.edit()
            .putFloat(typedKey, legacyValue.toFloat())
            .remove(legacyKey)
            .apply()
        return legacyValue
    }

    companion object {
        private const val NOTIFICATION_CHECK_PREFS_NAME = "notification_check_state"
        private const val NOTIFICATION_COOLDOWN_PREFS_NAME = "notif_dedup"
    }
}
