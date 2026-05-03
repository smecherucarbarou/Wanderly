package com.novahorizon.wanderly.ui.profile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAvatarFileProviderSourceTest {

    @Test
    fun `avatar crop destination uses FileProvider content uri under cache images`() {
        val source = readProjectFile("app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt")

        assertTrue(source.contains("File(requireContext().cacheDir, \"images\")"))
        assertTrue(source.contains("mkdirs()"))
        assertTrue(source.contains("FileProvider.getUriForFile"))
        assertTrue(source.contains("\${BuildConfig.APPLICATION_ID}.fileprovider"))
        assertTrue(source.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(source.contains("Intent.FLAG_GRANT_WRITE_URI_PERMISSION"))
        assertFalse(source.contains("Uri.fromFile"))
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
