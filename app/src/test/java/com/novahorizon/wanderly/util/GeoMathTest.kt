package com.novahorizon.wanderly.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun `distanceKm is zero for identical coordinates and symmetric`() {
        val bucharestLat = 44.4268
        val bucharestLng = 26.1025
        val londonLat = 51.5074
        val londonLng = -0.1278

        assertEquals(0.0, GeoMath.distanceKm(bucharestLat, bucharestLng, bucharestLat, bucharestLng), 0.000001)

        val bucharestToLondon = GeoMath.distanceKm(bucharestLat, bucharestLng, londonLat, londonLng)
        val londonToBucharest = GeoMath.distanceKm(londonLat, londonLng, bucharestLat, bucharestLng)

        assertEquals(bucharestToLondon, londonToBucharest, 0.000001)
        assertEquals(2093.0, bucharestToLondon, 25.0)
    }
}
