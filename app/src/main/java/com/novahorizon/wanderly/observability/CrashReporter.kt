package com.novahorizon.wanderly.observability

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.novahorizon.wanderly.BuildConfig

enum class CrashEvent(val reportName: String) {
    AUTH_CALLBACK_IMPORT_FAILED("auth_callback_import_failed"),
    AUTH_SIGN_OUT_FAILED("auth_sign_out_failed"),
    GEMINI_PROXY_CALL_FAILED("gemini_proxy_call_failed"),
    GOOGLE_PLACES_FALLBACK_FAILED("google_places_fallback_failed"),
    GEMS_LOAD_FAILED("gems_load_failed"),
    MISSION_FLOW_FAILED("mission_flow_failed"),
    MAP_LOCATION_UPDATE_FAILED("map_location_update_failed"),
    PROFILE_SYNC_FAILED("profile_sync_failed"),
    PROFILE_AVATAR_CROP_FAILED("profile_avatar_crop_failed"),
    PROFILE_AVATAR_UPLOAD_FAILED("profile_avatar_upload_failed"),
    STREAK_CHECK_FAILED("streak_check_failed"),
    SOCIAL_WORKER_FAILED("social_worker_failed"),
    STREAK_WORKER_FAILED("streak_worker_failed"),
    WIDGET_REFRESH_FAILED("widget_refresh_failed"),
    CRASHLYTICS_TEST_NON_FATAL("crashlytics_test_non_fatal")
}

enum class CrashKey(val keyName: String) {
    COMPONENT("component"),
    OPERATION("operation"),
    HTTP_STATUS("http_status"),
    RESULT("result")
}

interface CrashReportingBackend {
    fun setCollectionEnabled(enabled: Boolean)
    fun setCustomKey(key: String, value: String)
    fun recordException(throwable: Throwable)
}

object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val MAX_VALUE_LENGTH = 40
    private val sensitiveValuePatterns = listOf(
        Regex("(?i)(^|[^a-z0-9])(token|secret|password|authorization|bearer|api[_ -]?key|anon[_ -]?key|jwt)([^a-z0-9]|$)"),
        Regex("(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b"),
        Regex("(?i)(^|[^a-z0-9])(username|friend[_ -]?code|query|raw[_ -]?query|place[_ -]?name|raw[_ -]?place|avatar[_ -]?path)([^a-z0-9]|$)"),
        Regex("(?i)(^|[^a-z0-9])(lat|latitude|lng|lon|longitude)\\s*[:=]"),
        Regex("-?\\d{1,3}\\.\\d{4,}\\s*,\\s*-?\\d{1,3}\\.\\d{4,}"),
        Regex("(?i)(^|[^a-z0-9])(avatar|profile)[^\\s]*[\\\\/][^\\s]+")
    )
    private var backend: CrashReportingBackend? = null
    private var enabled: Boolean = false

    fun initialize(context: Context, configured: Boolean) {
        if (!configured) {
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "Crash reporting disabled; Firebase configuration is not present.")
            }
            backend = null
            enabled = false
            return
        }

        if (BuildConfig.DEBUG) {
            setFirebaseCollectionEnabledIfAvailable(context, enabled = false)
            backend = null
            enabled = false
            AppLogger.d(TAG, "Crash reporting disabled for debug builds.")
            return
        }

        initializeBackend(context, collectionEnabled = true, buildType = "release")
    }

    fun enableCollectionForExplicitTesting(context: Context): Boolean {
        if (!BuildConfig.CRASH_REPORTING_CONFIGURED) return false
        if (enabled && backend != null) return true
        return initializeBackend(
            context = context,
            collectionEnabled = true,
            buildType = if (BuildConfig.DEBUG) "debug_explicit_test" else "release"
        )
    }

    private fun initializeBackend(
        context: Context,
        collectionEnabled: Boolean,
        buildType: String
    ): Boolean {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                backend = null
                enabled = false
                AppLogger.w(TAG, "FirebaseApp is not initialized; Crashlytics disabled.")
                return false
            }

            backend = FirebaseCrashlyticsBackend(FirebaseCrashlytics.getInstance())
            enabled = collectionEnabled
            backend?.setCollectionEnabled(collectionEnabled)
            backend?.setCustomKey("build_type", buildType)
            backend?.setCustomKey("version_code", BuildConfig.VERSION_CODE.toString())
            backend?.setCustomKey("crash_reporting", "firebase_crashlytics")
            return enabled
        } catch (e: Throwable) {
            backend = null
            enabled = false
            AppLogger.w(TAG, "Crashlytics initialization skipped [${e.javaClass.simpleName}]")
            return false
        }
    }

    fun recordNonFatal(
        event: CrashEvent,
        throwable: Throwable,
        vararg keys: Pair<CrashKey, String>
    ): Boolean {
        val activeBackend = backend
        if (!enabled || activeBackend == null) return false

        activeBackend.setCustomKey("nonfatal_event", event.reportName)
        activeBackend.setCustomKey("cause_class", throwable.javaClass.simpleName)
        keys.forEach { (key, value) ->
            activeBackend.setCustomKey(key.keyName, sanitizeValue(value))
        }

        activeBackend.recordException(
            RedactedNonFatalException(
                eventName = event.reportName,
                causeClass = throwable.javaClass.simpleName,
                sourceStack = throwable.stackTrace
            )
        )
        return true
    }

    fun recordTestNonFatal(): Boolean {
        return recordNonFatal(
            event = CrashEvent.CRASHLYTICS_TEST_NON_FATAL,
            throwable = IllegalStateException("Crashlytics test non-fatal"),
            CrashKey.COMPONENT to "dev_dashboard",
            CrashKey.OPERATION to "manual_test"
        )
    }

    fun recordTestNonFatal(context: Context): Boolean {
        if (!enabled && !enableCollectionForExplicitTesting(context)) return false
        return recordTestNonFatal()
    }

    internal fun sanitizeValue(value: String): String {
        if (sensitiveValuePatterns.any { it.containsMatchIn(value) }) {
            return "redacted"
        }
        return value
            .trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^A-Za-z0-9_.:-]"), "")
            .take(MAX_VALUE_LENGTH)
            .ifBlank { "unknown" }
    }

    internal fun installBackendForTesting(backend: CrashReportingBackend, enabled: Boolean) {
        this.backend = backend
        this.enabled = enabled
    }

    internal fun resetForTesting() {
        backend = null
        enabled = false
    }

    private fun setFirebaseCollectionEnabledIfAvailable(context: Context, enabled: Boolean) {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Crashlytics collection toggle skipped [${e.javaClass.simpleName}]")
        }
    }

    private class FirebaseCrashlyticsBackend(
        private val crashlytics: FirebaseCrashlytics
    ) : CrashReportingBackend {
        override fun setCollectionEnabled(enabled: Boolean) {
            crashlytics.setCrashlyticsCollectionEnabled(enabled)
        }

        override fun setCustomKey(key: String, value: String) {
            crashlytics.setCustomKey(key, value)
        }

        override fun recordException(throwable: Throwable) {
            crashlytics.recordException(throwable)
        }
    }

    private class RedactedNonFatalException(
        eventName: String,
        causeClass: String,
        sourceStack: Array<StackTraceElement>
    ) : RuntimeException("nonfatal=$eventName cause=$causeClass") {
        init {
            stackTrace = sourceStack
        }
    }
}
