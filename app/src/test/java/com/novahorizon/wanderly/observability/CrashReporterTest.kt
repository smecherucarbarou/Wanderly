package com.novahorizon.wanderly.observability

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {

    @After
    fun tearDown() {
        CrashReporter.resetForTesting()
    }

    @Test
    fun recordNonFatalUsesRedactedThrowableAndLowCardinalityKeys() {
        val backend = FakeCrashBackend()
        CrashReporter.installBackendForTesting(backend, enabled = true)
        val sensitiveMessage = "failed for token=abc123 at place=Secret Cafe"
        val original = IllegalStateException(sensitiveMessage).apply {
            stackTrace = arrayOf(StackTraceElement("SourceClass", "sourceMethod", "SourceClass.kt", 42))
        }

        CrashReporter.recordNonFatal(
            event = CrashEvent.GOOGLE_PLACES_FALLBACK_FAILED,
            throwable = original,
            CrashKey.COMPONENT to "gems feed",
            CrashKey.HTTP_STATUS to "500;DROP TABLE"
        )

        assertEquals("gems_feed", backend.keys["component"])
        assertEquals("500DROP_TABLE", backend.keys["http_status"])
        assertEquals("google_places_fallback_failed", backend.keys["nonfatal_event"])
        assertEquals("IllegalStateException", backend.keys["cause_class"])
        assertEquals(1, backend.recorded.size)
        val reported = backend.recorded.single()
        assertTrue(reported.message.orEmpty().contains("google_places_fallback_failed"))
        assertTrue(reported.message.orEmpty().contains("IllegalStateException"))
        assertFalse(reported.message.orEmpty().contains(sensitiveMessage))
        assertFalse(reported.stackTraceToString().contains(sensitiveMessage))
        assertEquals("SourceClass", reported.stackTrace.single().className)
    }

    @Test
    fun recordNonFatalDoesNothingWhenDisabled() {
        val backend = FakeCrashBackend()
        CrashReporter.installBackendForTesting(backend, enabled = false)

        val recorded = CrashReporter.recordNonFatal(
            event = CrashEvent.PROFILE_SYNC_FAILED,
            throwable = RuntimeException("ignored")
        )

        assertFalse(recorded)
        assertTrue(backend.keys.isEmpty())
        assertTrue(backend.recorded.isEmpty())
    }

    @Test
    fun recordTestNonFatalUsesSyntheticLowCardinalityPayload() {
        val backend = FakeCrashBackend()
        CrashReporter.installBackendForTesting(backend, enabled = true)

        val recorded = CrashReporter.recordTestNonFatal()

        assertTrue(recorded)
        assertEquals("crashlytics_test_non_fatal", backend.keys["nonfatal_event"])
        assertEquals("dev_dashboard", backend.keys["component"])
        assertEquals("manual_test", backend.keys["operation"])
        val reported = backend.recorded.single()
        assertTrue(reported.message.orEmpty().contains("crashlytics_test_non_fatal"))
        assertFalse(reported.stackTraceToString().contains("@"))
        assertFalse(reported.stackTraceToString().contains("token"))
    }

    @Test
    fun sanitizeValueRedactsKnownSensitiveInputShapes() {
        val sensitiveValues = listOf(
            "token=abc123",
            "person@example.com",
            "username=BeeQueen",
            "friend code ABC123",
            "44.426800, 26.102500",
            "query=coffee near me",
            "avatar/content/profile.png"
        )

        sensitiveValues.forEach { value ->
            assertEquals("redacted", CrashReporter.sanitizeValue(value))
        }
    }

    private class FakeCrashBackend : CrashReportingBackend {
        val keys = linkedMapOf<String, String>()
        val recorded = mutableListOf<Throwable>()
        var collectionEnabled: Boolean? = null

        override fun setCollectionEnabled(enabled: Boolean) {
            collectionEnabled = enabled
        }

        override fun setCustomKey(key: String, value: String) {
            keys[key] = value
        }

        override fun recordException(throwable: Throwable) {
            recorded += throwable
        }
    }
}
