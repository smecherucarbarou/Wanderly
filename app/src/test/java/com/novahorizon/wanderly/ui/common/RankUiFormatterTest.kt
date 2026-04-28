package com.novahorizon.wanderly.ui.common

import com.novahorizon.wanderly.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankUiFormatterTest {

    @Test
    fun `rankNameRes maps rank buckets to string resources`() {
        assertEquals(R.string.rank_1, RankUiFormatter.rankNameRes(1))
        assertEquals(R.string.rank_2, RankUiFormatter.rankNameRes(2))
        assertEquals(R.string.rank_3, RankUiFormatter.rankNameRes(3))
        assertEquals(R.string.rank_4, RankUiFormatter.rankNameRes(4))
        assertEquals(R.string.rank_4, RankUiFormatter.rankNameRes(99))
        assertEquals(R.string.rank_4, RankUiFormatter.rankNameRes(0))
    }

    @Test
    fun `rank displays use shared formatter instead of fragment copies`() {
        val callSites = listOf(
            projectFile("app/src/main/java/com/novahorizon/wanderly/ui/missions/MissionsFragment.kt"),
            projectFile("app/src/main/java/com/novahorizon/wanderly/ui/profile/ProfileFragment.kt"),
            projectFile("app/src/main/java/com/novahorizon/wanderly/ui/social/SocialFragment.kt")
        )

        callSites.forEach { file ->
            val source = file.readText()
            assertFalse("${file.name} should not keep a private rank formatter", source.contains("private fun getRankName"))
            assertTrue("${file.name} should use RankUiFormatter", source.contains("RankUiFormatter.rankNameRes"))
        }
    }

    private fun projectFile(relativePath: String): File = File(projectRoot(), relativePath)

    private fun projectRoot(): File {
        var current = File("").absoluteFile
        while (!File(current, "settings.gradle.kts").exists()) {
            current = current.parentFile ?: return current
        }
        return current
    }
}
