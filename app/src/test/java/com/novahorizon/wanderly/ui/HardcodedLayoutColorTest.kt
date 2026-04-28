package com.novahorizon.wanderly.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardcodedLayoutColorTest {

    @Test
    fun `dev dashboard and gem item use color resources for themed surfaces`() {
        val root = projectRoot()
        val devDashboard = File(root, "app/src/main/res/layout/fragment_dev_dashboard.xml").readText()
        val gemItem = File(root, "app/src/main/res/layout/item_gem.xml").readText()
        val dayColors = File(root, "app/src/main/res/values/colors.xml").readText()
        val nightColors = File(root, "app/src/main/res/values-night/colors.xml").readText()

        assertFalse(devDashboard.contains("#1A000000"))
        assertFalse(gemItem.contains("#11000000"))
        assertTrue(devDashboard.contains("@color/dev_log_background"))
        assertTrue(gemItem.contains("@color/gem_divider"))
        assertTrue(dayColors.contains("""name="dev_log_background""""))
        assertTrue(dayColors.contains("""name="gem_divider""""))
        assertTrue(nightColors.contains("""name="dev_log_background""""))
        assertTrue(nightColors.contains("""name="gem_divider""""))
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
