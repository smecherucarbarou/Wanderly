package com.novahorizon.wanderly.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoutCoordinatorTest {

    @Test
    fun `logout coordinator signs out and runs every cleanup step`() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = LogoutCoordinator(
            signOut = { calls += "signOut" },
            stopRealtime = { calls += "stopRealtime" },
            cancelUserWork = { calls += "cancelUserWork" },
            clearNotificationState = { calls += "clearNotificationState" },
            clearLocalState = { calls += "clearLocalState" },
            cancelWidgetRefresh = { calls += "cancelWidgetRefresh" }
        )

        val result = coordinator.logoutCompletely()

        assertTrue(result.signedOut)
        assertTrue(result.failures.isEmpty())
        assertEquals(
            listOf(
                "signOut",
                "stopRealtime",
                "cancelUserWork",
                "clearNotificationState",
                "clearLocalState",
                "cancelWidgetRefresh"
            ),
            calls
        )
    }

    @Test
    fun `cleanup failure does not skip sign out or later cleanup`() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = LogoutCoordinator(
            signOut = { calls += "signOut" },
            stopRealtime = {
                calls += "stopRealtime"
                error("service stop failed")
            },
            cancelUserWork = { calls += "cancelUserWork" },
            clearNotificationState = { calls += "clearNotificationState" },
            clearLocalState = { calls += "clearLocalState" },
            cancelWidgetRefresh = { calls += "cancelWidgetRefresh" }
        )

        val result = coordinator.logoutCompletely()

        assertTrue(result.signedOut)
        assertFalse(result.success)
        assertEquals(listOf(LogoutCleanupStep.STOP_REALTIME), result.failures.map { it.step })
        assertEquals(
            listOf(
                "signOut",
                "stopRealtime",
                "cancelUserWork",
                "clearNotificationState",
                "clearLocalState",
                "cancelWidgetRefresh"
            ),
            calls
        )
    }
}
