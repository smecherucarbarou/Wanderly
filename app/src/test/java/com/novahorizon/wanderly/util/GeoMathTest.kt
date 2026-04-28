package com.novahorizon.wanderly.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `repositories use shared GeoMath distance implementation`() {
        val discovery = projectFile("app/src/main/java/com/novahorizon/wanderly/data/DiscoveryRepository.kt")
            .readText()
        val google = projectFile("app/src/main/java/com/novahorizon/wanderly/data/GooglePlacesDataSource.kt")
            .readText()

        assertFalse(discovery.contains("private fun distanceKm"))
        assertFalse(google.contains("private fun distanceKm"))
        assertTrue(discovery.contains("GeoMath.distanceKm"))
        assertTrue(google.contains("GeoMath.distanceKm"))
    }

    private fun projectFile(relativePath: String): File {
        return File(projectRoot(), relativePath)
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        var current: File? = File(userDir).absoluteFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile
        }
        error("Project root not found")
    }
}
