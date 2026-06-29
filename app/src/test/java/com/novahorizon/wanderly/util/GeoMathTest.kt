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

    @Test
    fun `antipodal points are half the circumference apart`() {
        // (0,0) and (0,180) are antipodal: distance == pi * R.
        assertEquals(20015.09, GeoMath.distanceKm(0.0, 0.0, 0.0, 180.0), 1.0)
    }

    @Test
    fun `distance is correct across the antimeridian`() {
        // 0.2 deg apart at the equator (~22.2 km); haversine must wrap, not return ~40000 km.
        assertEquals(22.24, GeoMath.distanceKm(0.0, 179.9, 0.0, -179.9), 0.5)
    }

    @Test
    fun `distance is correct across the equator`() {
        // 2 deg of latitude (1N to 1S) ~= 222.4 km.
        assertEquals(222.39, GeoMath.distanceKm(1.0, 0.0, -1.0, 0.0), 1.0)
    }

    @Test
    fun `points at the same pole are effectively coincident regardless of longitude`() {
        assertEquals(0.0, GeoMath.distanceKm(90.0, 0.0, 90.0, 123.0), 0.001)
    }

    @Test
    fun `sub-100-meter separation resolves to a few meters`() {
        // 0.0001 deg of latitude ~= 11.1 m.
        assertEquals(0.01112, GeoMath.distanceKm(10.0, 10.0, 10.0001, 10.0), 0.0005)
    }
}
