package com.novahorizon.wanderly.ui.profile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

class UCropManifestTest {

    @Test
    fun `UCropActivity does not force portrait orientation`() {
        val activity = manifestActivities()
            .firstOrNull { it.androidAttribute("name") == "com.yalantis.ucrop.UCropActivity" }

        assertNotNull("UCropActivity should be declared in the app manifest", activity)
        assertFalse(
            "UCropActivity should not lock the image cropper to portrait",
            activity!!.hasAttributeNS(ANDROID_NS, "screenOrientation")
        )
    }

    private fun manifestActivities(): List<Element> {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val nodes = document.getElementsByTagName("activity")
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
