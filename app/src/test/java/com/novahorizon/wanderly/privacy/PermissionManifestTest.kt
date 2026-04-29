package com.novahorizon.wanderly.privacy

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class PermissionManifestTest {

    @Test
    fun `location permissions do not claim neverForLocation`() {
        val locationPermissions = manifestPermissions()
            .filter {
                it.androidAttribute("name") == "android.permission.ACCESS_FINE_LOCATION" ||
                    it.androidAttribute("name") == "android.permission.ACCESS_COARSE_LOCATION"
            }

        locationPermissions.forEach { permission ->
            assertFalse(
                "Location permissions must not use neverForLocation",
                permission.hasAttributeNS(ANDROID_NS, "usesPermissionFlags")
            )
        }
    }

    private fun manifestPermissions(): List<Element> {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val nodes = document.getElementsByTagName("uses-permission")
        return (0 until nodes.length)
            .map { nodes.item(it) }
            .filterIsInstance<Element>()
    }

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
