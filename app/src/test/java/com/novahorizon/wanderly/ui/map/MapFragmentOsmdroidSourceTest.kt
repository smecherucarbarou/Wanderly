package com.novahorizon.wanderly.ui.map

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFragmentOsmdroidSourceTest {

    @Test
    fun `map fragment waits for async osmdroid init before creating map view`() {
        val fragmentSource = projectFile(
            "app/src/main/java/com/novahorizon/wanderly/ui/map/MapFragment.kt"
        ).readText()
        val layoutSource = projectFile("app/src/main/res/layout/fragment_map.xml").readText()

        assertFalse(fragmentSource.contains("Configuration.getInstance()"))
        assertFalse(fragmentSource.contains("cacheDir.resolve(\"osmdroid\")"))
        assertFalse(fragmentSource.contains("getCacheDir("))
        assertFalse(fragmentSource.contains("OsmdroidInitializer"))
        assertFalse(fragmentSource.contains("MapView(requireContext())"))
        assertTrue(layoutSource.contains("org.osmdroid.views.MapView"))
        assertTrue(layoutSource.contains("@+id/map_view"))
    }

    @Test
    fun `osmdroid configuration stays on io dispatcher during application startup`() {
        val appSource = projectFile(
            "app/src/main/java/com/novahorizon/wanderly/WanderlyApplication.kt"
        ).readText()
        val initializerSource = projectFile(
            "app/src/main/java/com/novahorizon/wanderly/OsmdroidInitializer.kt"
        ).readText()

        assertTrue(appSource.contains("OsmdroidInitializer.start(this, appScope)"))
        assertTrue(initializerSource.contains("Dispatchers.IO"))
        assertTrue(initializerSource.contains("Configuration.getInstance().load"))
        assertTrue(initializerSource.contains("cacheDir.resolve(\"osmdroid\")"))
    }

    private fun projectFile(relativePath: String): File = File(projectRoot(), relativePath)

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }
}
