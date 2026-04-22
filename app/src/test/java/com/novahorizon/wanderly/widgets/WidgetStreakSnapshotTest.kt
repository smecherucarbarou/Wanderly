package com.novahorizon.wanderly.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetStreakSnapshotTest {

    @Test
    fun fromPersistedValuesReturnsSnapshotWhenValuesArePresent() {
        val snapshot = WidgetStreakSnapshot.fromPersistedValues(
            streakCount = 18,
            lastMissionDate = "2026-04-21",
            savedAtMillis = 123456789L,
            lastSyncSucceeded = true
        )

        assertEquals(
            WidgetStreakSnapshot(
                streakCount = 18,
                lastMissionDate = "2026-04-21",
                savedAtMillis = 123456789L,
                lastSyncSucceeded = true
            ),
            snapshot
        )
    }

    @Test
    fun fromPersistedValuesReturnsNullWhenNothingIsStored() {
        val snapshot = WidgetStreakSnapshot.fromPersistedValues(
            streakCount = null,
            lastMissionDate = null,
            savedAtMillis = null,
            lastSyncSucceeded = null
        )

        assertNull(snapshot)
    }
}
