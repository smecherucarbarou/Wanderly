package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseDatabaseDefinitionTest {

    @Test
    fun `fixed database definition scopes private profiles and public profile view`() {
        val sql = readSqlDefinition()
        val publicProfilesView = sql.substringAfter("CREATE OR REPLACE VIEW public.profiles_public")
            .substringBefore("REVOKE ALL ON public.profiles_public")

        assertTrue(sql.contains("CREATE POLICY \"Users read own profile\""))
        assertTrue(sql.contains("USING (auth.uid() = id)"))
        assertTrue(sql.contains("ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY"))
        assertTrue(sql.contains("CREATE OR REPLACE VIEW public.profiles_public"))
        assertTrue(publicProfilesView.contains("WITH (security_invoker = true)"))
        assertFalse(publicProfilesView.contains("SECURITY DEFINER", ignoreCase = true))
        assertFalse(sql.contains("CREATE POLICY \"Anyone can view profiles\""))
        assertFalse(publicProfilesView.contains("last_lat"))
        assertFalse(publicProfilesView.contains("last_lng"))
    }

    @Test
    fun `fixed database definition enforces pending friendship workflow`() {
        val sql = readSqlDefinition()

        assertTrue(sql.contains("ALTER TABLE public.friendships ENABLE ROW LEVEL SECURITY"))
        assertTrue(sql.contains("status text NOT NULL DEFAULT 'pending'"))
        assertTrue(sql.contains("CHECK (status IN ('pending', 'accepted', 'blocked'))"))
        assertTrue(sql.contains("CREATE POLICY \"Users insert own pending friendship\""))
        assertTrue(sql.contains("WITH CHECK (auth.uid() = user_id AND status = 'pending')"))
        assertTrue(sql.contains("CREATE POLICY \"Recipient can accept or block friendship\""))
    }

    @Test
    fun `fixed database definition omits duplicate indexes and converts mission date`() {
        val sql = readSqlDefinition()

        assertTrue(sql.contains("last_mission_date date"))
        assertFalse(sql.contains("CREATE INDEX idx_profiles_friend_code"))
        assertFalse(sql.contains("CREATE INDEX idx_profiles_id"))
        assertFalse(sql.contains("CREATE INDEX idx_profiles_username"))
        assertFalse(sql.contains("CREATE INDEX idx_friendships_friend_id"))
    }

    private fun readSqlDefinition(): String {
        val sqlPath = "supabase/wanderly_db_fixed.sql"
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        val root = generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, sqlPath).isFile }
            ?: error("Could not find project root containing $sqlPath")

        return File(root, sqlPath).readText()
    }

}
