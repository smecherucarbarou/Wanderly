package com.novahorizon.wanderly.ui.missions

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionCameraFileProviderTest {

    @Test
    fun `mission camera temp file is created under cache images directory`() {
        val source = readProjectFile("app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt")

        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("File(context.cacheDir, \"images\")"))
        assertTrue(source.contains(".apply {"))
        assertTrue(source.contains("mkdirs()"))
        assertTrue(source.contains("File.createTempFile(\"mission_verify_\", \".jpg\", imagesDir)"))
        assertFalse(source.contains("File.createTempFile(\"mission_verify_\", \".jpg\", requireContext().cacheDir)"))
    }

    @Test
    fun `file provider roots remain narrow`() {
        val xml = readProjectFile("app/src/main/res/xml/file_paths.xml")

        assertTrue(xml.contains("<cache-path name=\"my_images\" path=\"images/\""))
        assertFalse(xml.contains("<root-path"))
        assertFalse(xml.contains("path=\".\""))
    }

    private fun readProjectFile(path: String): String {
        val file = projectRoot().resolve(path)
        require(file.isFile) { "Missing required file: $path" }
        return file.readText()
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }
}
