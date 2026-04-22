package com.novahorizon.wanderly.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novahorizon.wanderly.Constants
import kotlinx.coroutines.flow.first

private const val MAIN_DATASTORE_NAME = "wanderly_main_store"
private const val NOTIFICATION_CHECK_DATASTORE_NAME = "wanderly_notification_check_store"
private const val NOTIFICATION_COOLDOWN_DATASTORE_NAME = "wanderly_notification_cooldown_store"

private val Context.mainDataStore: DataStore<Preferences> by preferencesDataStore(
    name = MAIN_DATASTORE_NAME,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, Constants.PREFS_NAME))
    }
)

private val Context.notificationCheckDataStore: DataStore<Preferences> by preferencesDataStore(
    name = NOTIFICATION_CHECK_DATASTORE_NAME,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, PreferencesStore.NOTIFICATION_CHECK_PREFS_NAME))
    }
)

private val Context.notificationCooldownDataStore: DataStore<Preferences> by preferencesDataStore(
    name = NOTIFICATION_COOLDOWN_DATASTORE_NAME,
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, PreferencesStore.NOTIFICATION_COOLDOWN_PREFS_NAME))
    }
)

class DataStoreManager(context: Context) {
    private val appContext = context.applicationContext

    private enum class StoreType {
        MAIN,
        NOTIFICATION_CHECK,
        NOTIFICATION_COOLDOWN
    }

    suspend fun getMainBoolean(key: String, default: Boolean = false): Boolean =
        getBoolean(StoreType.MAIN, key, default)

    suspend fun putMainBoolean(key: String, value: Boolean) {
        putBoolean(StoreType.MAIN, key, value)
    }

    suspend fun getMainString(key: String, default: String? = null): String? =
        getString(StoreType.MAIN, key, default)

    suspend fun putMainString(key: String, value: String?) {
        putString(StoreType.MAIN, key, value)
    }

    suspend fun getMainFloat(key: String): Float? =
        getFloat(StoreType.MAIN, key)

    suspend fun putMainFloat(key: String, value: Float) {
        putFloat(StoreType.MAIN, key, value)
    }

    suspend fun getMainInt(key: String, default: Int = 0): Int =
        getInt(StoreType.MAIN, key, default)

    suspend fun putMainInt(key: String, value: Int) {
        putInt(StoreType.MAIN, key, value)
    }

    suspend fun getMainLong(key: String, default: Long = 0L): Long =
        getLong(StoreType.MAIN, key, default)

    suspend fun putMainLong(key: String, value: Long) {
        putLong(StoreType.MAIN, key, value)
    }

    suspend fun removeMainKeys(keys: Iterable<String>) {
        removeKeys(StoreType.MAIN, keys)
    }

    suspend fun clearMainStore() {
        clearStore(StoreType.MAIN)
    }

    suspend fun getNotificationCooldown(key: String): Long =
        getLong(StoreType.NOTIFICATION_COOLDOWN, key, 0L)

    suspend fun setNotificationCooldown(key: String, value: Long) {
        putLong(StoreType.NOTIFICATION_COOLDOWN, key, value)
    }

    suspend fun clearNotificationCooldowns() {
        clearStore(StoreType.NOTIFICATION_COOLDOWN)
    }

    suspend fun getNotificationCooldownKeys(): Set<String> =
        getKeys(StoreType.NOTIFICATION_COOLDOWN)

    suspend fun removeNotificationCooldown(key: String) {
        removeKeys(StoreType.NOTIFICATION_COOLDOWN, listOf(key))
    }

    suspend fun getNotificationCheckString(key: String): String? =
        getString(StoreType.NOTIFICATION_CHECK, key, null)

    suspend fun putNotificationCheckString(key: String, value: String) {
        putString(StoreType.NOTIFICATION_CHECK, key, value)
    }

    suspend fun getNotificationCheckBoolean(key: String): Boolean =
        getBoolean(StoreType.NOTIFICATION_CHECK, key, false)

    suspend fun putNotificationCheckBoolean(key: String, value: Boolean) {
        putBoolean(StoreType.NOTIFICATION_CHECK, key, value)
    }

    suspend fun removeNotificationCheckKeys(keys: Iterable<String>) {
        removeKeys(StoreType.NOTIFICATION_CHECK, keys)
    }

    suspend fun clearNotificationCheckState() {
        clearStore(StoreType.NOTIFICATION_CHECK)
    }

    suspend fun getNotificationCheckKeys(): Set<String> =
        getKeys(StoreType.NOTIFICATION_CHECK)

    private suspend fun getString(storeType: StoreType, key: String, default: String?): String? {
        val prefs = dataStore(storeType).data.first()
        return prefs[stringPreferencesKey(key)] ?: default
    }

    private suspend fun putString(storeType: StoreType, key: String, value: String?) {
        dataStore(storeType).edit { prefs ->
            val prefKey = stringPreferencesKey(key)
            if (value == null) {
                prefs.remove(prefKey)
            } else {
                prefs[prefKey] = value
            }
        }
    }

    private suspend fun getBoolean(storeType: StoreType, key: String, default: Boolean): Boolean {
        val prefs = dataStore(storeType).data.first()
        return prefs[booleanPreferencesKey(key)] ?: default
    }

    private suspend fun putBoolean(storeType: StoreType, key: String, value: Boolean) {
        dataStore(storeType).edit { prefs ->
            prefs[booleanPreferencesKey(key)] = value
        }
    }

    private suspend fun getLong(storeType: StoreType, key: String, default: Long): Long {
        val prefs = dataStore(storeType).data.first()
        return prefs[longPreferencesKey(key)] ?: default
    }

    private suspend fun putLong(storeType: StoreType, key: String, value: Long) {
        dataStore(storeType).edit { prefs ->
            prefs[longPreferencesKey(key)] = value
        }
    }

    private suspend fun getFloat(storeType: StoreType, key: String): Float? {
        val prefs = dataStore(storeType).data.first()
        return prefs[floatPreferencesKey(key)]
    }

    private suspend fun putFloat(storeType: StoreType, key: String, value: Float) {
        dataStore(storeType).edit { prefs ->
            prefs[floatPreferencesKey(key)] = value
        }
    }

    private suspend fun getInt(storeType: StoreType, key: String, default: Int): Int {
        val prefs = dataStore(storeType).data.first()
        return prefs[intPreferencesKey(key)] ?: default
    }

    private suspend fun putInt(storeType: StoreType, key: String, value: Int) {
        dataStore(storeType).edit { prefs ->
            prefs[intPreferencesKey(key)] = value
        }
    }

    private suspend fun removeKeys(storeType: StoreType, keys: Iterable<String>) {
        dataStore(storeType).edit { prefs ->
            keys.forEach { key ->
                prefs.remove(stringPreferencesKey(key))
                prefs.remove(booleanPreferencesKey(key))
                prefs.remove(longPreferencesKey(key))
                prefs.remove(floatPreferencesKey(key))
                prefs.remove(intPreferencesKey(key))
            }
        }
    }

    private suspend fun clearStore(storeType: StoreType) {
        dataStore(storeType).edit { prefs ->
            prefs.clear()
        }
    }

    private suspend fun getKeys(storeType: StoreType): Set<String> {
        return dataStore(storeType).data.first().asMap().keys.map { it.name }.toSet()
    }

    private fun dataStore(storeType: StoreType): DataStore<Preferences> {
        return when (storeType) {
            StoreType.MAIN -> appContext.mainDataStore
            StoreType.NOTIFICATION_CHECK -> appContext.notificationCheckDataStore
            StoreType.NOTIFICATION_COOLDOWN -> appContext.notificationCooldownDataStore
        }
    }
}
