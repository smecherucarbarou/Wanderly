package com.novahorizon.wanderly.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferencesStoreLegacyCoordinateTest {

    @Test
    fun parseLegacyMissionCoordinateReturnsNullForInvalidValue() {
        assertNull(PreferencesStore.parseLegacyMissionCoordinate("not-a-coordinate"))
    }

    @Test
    fun parseLegacyMissionCoordinateParsesValidValue() {
        assertEquals(
            44.4268,
            PreferencesStore.parseLegacyMissionCoordinate("44.4268") ?: error("Expected coordinate"),
            0.00001
        )
    }
}
