package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseDatabaseDefinitionTest {

    @Test
    fun `fixed database definition scopes private profiles and public profile view`() {
        val sql = fixedDatabaseDefinition().readText()

        assertTrue(sql.contains("CREATE POLICY \"Users read own profile\""))
        assertTrue(sql.contains("USING (auth.uid() = id)"))
        assertTrue(sql.contains("CREATE OR REPLACE VIEW public.profiles_public AS"))
        assertFalse(sql.contains("CREATE POLICY \"Anyone can view profiles\""))
        assertFalse(sql.contains("last_lat", after = "CREATE OR REPLACE VIEW public.profiles_public AS"))
        assertFalse(sql.contains("last_lng", after = "CREATE OR REPLACE VIEW public.profiles_public AS"))
    }

    @Test
    fun `fixed database definition enforces pending friendship workflow`() {
        val sql = fixedDatabaseDefinition().readText()

        assertTrue(sql.contains("status text NOT NULL DEFAULT 'pending'"))
        assertTrue(sql.contains("CHECK (status IN ('pending', 'accepted', 'blocked'))"))
        assertTrue(sql.contains("CREATE POLICY \"Users insert own pending friendship\""))
        assertTrue(sql.contains("WITH CHECK (auth.uid() = user_id AND status = 'pending')"))
        assertTrue(sql.contains("CREATE POLICY \"Recipient can accept or block friendship\""))
    }

    @Test
    fun `fixed database definition omits duplicate indexes and converts mission date`() {
        val sql = fixedDatabaseDefinition().readText()

        assertTrue(sql.contains("last_mission_date date"))
        assertFalse(sql.contains("CREATE INDEX idx_profiles_friend_code"))
        assertFalse(sql.contains("CREATE INDEX idx_profiles_id"))
        assertFalse(sql.contains("CREATE INDEX idx_profiles_username"))
        assertFalse(sql.contains("CREATE INDEX idx_friendships_friend_id"))
    }

    private fun fixedDatabaseDefinition(): File {
        return File(projectRoot(), "supabase/wanderly_db_fixed.sql")
    }

    private fun String.contains(needle: String, after: String): Boolean {
        val startIndex = indexOf(after)
        if (startIndex == -1) return false
        return indexOf(needle, startIndex) != -1
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
