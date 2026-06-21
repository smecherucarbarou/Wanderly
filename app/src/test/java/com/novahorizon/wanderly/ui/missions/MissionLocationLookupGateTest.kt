package com.novahorizon.wanderly.ui.missions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionLocationLookupGateTest {

    @Test
    fun `gate rejects duplicate starts until current lookup finishes`() {
        val gate = MissionLocationLookupGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())

        gate.finish()

        assertTrue(gate.tryStart())
    }
}
