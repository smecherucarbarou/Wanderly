package com.novahorizon.wanderly

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SplashScreenArchitectureTest {

    @Test
    fun `SplashActivity uses AndroidX SplashScreen API instead of custom splash view`() {
        val splashActivity = projectFile("app/src/main/java/com/novahorizon/wanderly/SplashActivity.kt")
            .readText()

        assertTrue(splashActivity.contains("installSplashScreen()"))
        assertTrue(splashActivity.contains("setKeepOnScreenCondition"))
        assertFalse(splashActivity.contains("ActivitySplashBinding"))
        assertFalse(splashActivity.contains("setContentView"))
        assertFalse(splashActivity.contains("ObjectAnimator"))
        assertFalse(splashActivity.contains("AnimatorSet"))
        assertFalse(splashActivity.contains("startAnimations"))
        assertFalse(projectFile("app/src/main/res/layout/activity_splash.xml").exists())
    }

    @Test
    fun `launcher activity uses SplashScreen theme`() {
        val splashActivity = manifestActivities()
            .first { it.androidAttribute("name") == ".SplashActivity" }

        assertEquals("@style/Theme.Wanderly.Starting", splashActivity.androidAttribute("theme"))
    }

    @Test
    fun `app defines AndroidX SplashScreen theme and dependency`() {
        val startingTheme = resourceStyles("app/src/main/res/values/themes.xml")
            .first { it.getAttribute("name") == "Theme.Wanderly.Starting" }

        assertEquals("Theme.SplashScreen", startingTheme.getAttribute("parent"))
        assertTrue(startingTheme.containsItem("windowSplashScreenBackground", "@color/background"))
        assertTrue(startingTheme.containsItem("windowSplashScreenAnimatedIcon", "@drawable/ic_honeycomb"))
        assertTrue(startingTheme.containsItem("postSplashScreenTheme", "@style/Theme.Wanderly"))

        val versionsCatalog = projectFile("gradle/libs.versions.toml").readText()
        val appBuild = projectFile("app/build.gradle.kts").readText()
        assertTrue(versionsCatalog.contains("coreSplashscreen = \"1.2.0\""))
        assertTrue(versionsCatalog.contains("androidx-core-splashscreen"))
        assertTrue(appBuild.contains("implementation(libs.androidx.core.splashscreen)"))
    }

    private fun Element.containsItem(name: String, value: String): Boolean {
        val nodes = getElementsByTagName("item")
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
            .any { it.getAttribute("name") == name && it.textContent.trim() == value }
    }

    private fun manifestActivities(): List<Element> {
        val document = parseXml(projectFile("app/src/main/AndroidManifest.xml"))
        val nodes = document.getElementsByTagName("activity")
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
    }

    private fun resourceStyles(relativePath: String): List<Element> {
        val document = parseXml(projectFile(relativePath))
        val nodes = document.getElementsByTagName("style")
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(file)

    private fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NS, name)

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

    private companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
