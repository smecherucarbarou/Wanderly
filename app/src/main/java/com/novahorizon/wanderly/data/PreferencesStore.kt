package com.novahorizon.wanderly.data

import android.content.Context
import android.content.SharedPreferences
import com.novahorizon.wanderly.Constants

class PreferencesStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

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

    fun getMissionTargetCoordinates(): Pair<Double, Double>? {
        val lat = prefs.getString(Constants.KEY_MISSION_TARGET_LAT, null)?.toDoubleOrNull()
        val lng = prefs.getString(Constants.KEY_MISSION_TARGET_LNG, null)?.toDoubleOrNull()
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
            .putString(Constants.KEY_MISSION_TARGET_LAT, targetLat.toString())
            .putString(Constants.KEY_MISSION_TARGET_LNG, targetLng.toString())
            .apply()
    }

    fun clearMissionData() {
        prefs.edit()
            .remove(Constants.KEY_MISSION_TEXT)
            .remove(Constants.KEY_MISSION_TARGET)
            .remove(Constants.KEY_MISSION_CITY)
            .remove(Constants.KEY_MISSION_TARGET_LAT)
            .remove(Constants.KEY_MISSION_TARGET_LNG)
            .apply()
    }
}
