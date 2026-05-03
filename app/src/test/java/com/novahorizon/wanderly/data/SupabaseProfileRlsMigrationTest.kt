package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseProfileRlsMigrationTest {

    @Test
    fun `profile rls migration removes broad profile reads and exposes only safe public columns`() {
        val migration = readMigration("fix_profiles_rls_public_view")

        assertTrue(migration.contains("DROP POLICY IF EXISTS \"Users can read profiles for social\""))
        assertTrue(migration.contains("ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY"))
        assertTrue(migration.contains("CREATE POLICY \"profiles_select_own\""))
        assertTrue(migration.contains("USING (id = auth.uid())"))
        assertTrue(migration.contains("CREATE POLICY \"profiles_insert_own\""))
        assertTrue(migration.contains("WITH CHECK (id = auth.uid())"))
        assertTrue(migration.contains("CREATE POLICY \"profiles_update_own\""))

        val publicView = migration.substringAfter("CREATE VIEW public.profiles_public")
            .substringBefore("REVOKE ALL ON public.profiles_public")

        assertTrue(publicView.contains("WITH (security_invoker = true)"))
        assertTrue(publicView.contains("friend_code"))
        assertFalse(publicView.contains("last_lat"))
        assertFalse(publicView.contains("last_lng"))
        assertFalse(publicView.contains("admin_role"))
        assertFalse(publicView.contains("last_mission_date"))
        assertFalse(publicView.contains("updated_at"))
        assertFalse(publicView.contains("email"))
        assertFalse(publicView.contains("phone"))
        assertFalse(publicView.contains("*"))
        assertTrue(migration.contains("CREATE OR REPLACE FUNCTION public.find_profile_by_friend_code"))
        assertTrue(migration.contains("CREATE OR REPLACE FUNCTION public.get_accepted_friend_profiles"))
        assertTrue(migration.contains("CREATE OR REPLACE FUNCTION public.get_social_leaderboard"))
        assertTrue(migration.contains("SECURITY DEFINER"))
        assertTrue(migration.contains("auth.uid() IS NOT NULL"))
    }

    @Test
    fun `profile rls isolation verification script covers two users and public view columns`() {
        val verification = readProjectFile("supabase/tests/profile_rls_isolation.sql")

        assertTrue(verification.contains("User A cannot SELECT User B"))
        assertTrue(verification.contains("User A can SELECT own profile"))
        assertTrue(verification.contains("User A cannot UPDATE User B profile"))
        assertTrue(verification.contains("profiles_public does not contain private columns"))
        assertTrue(verification.contains("Unauthenticated access is denied"))
        assertTrue(verification.contains("last_lat"))
        assertTrue(verification.contains("last_lng"))
        assertTrue(verification.contains("admin_role"))
        assertTrue(verification.contains("last_mission_date"))
        assertTrue(verification.contains("updated_at"))
    }

    @Test
    fun `profile rls migration adds admin select and update policies`() {
        val migration = readMigration("add_admin_profile_policies")

        assertTrue(migration.contains("CREATE INDEX IF NOT EXISTS idx_profiles_admin_role"))
        assertTrue(migration.contains("WHERE admin_role = true"))
        assertTrue(migration.contains("CREATE POLICY \"admins_select_all_profiles\""))
        assertTrue(migration.contains("FOR SELECT"))
        assertTrue(migration.contains("CREATE POLICY \"admins_update_any_profile\""))
        assertTrue(migration.contains("FOR UPDATE"))
        assertTrue(migration.contains("p.id = auth.uid()"))
        assertTrue(migration.contains("p.admin_role = true"))
        assertTrue(migration.contains("WITH CHECK"))
        assertFalse(migration.contains("SET admin_role"))
    }

    @Test
    fun `profile rls verification script covers admin update bypass`() {
        val verification = readProjectFile("supabase/tests/profile_rls_isolation.sql")

        assertTrue(verification.contains("admin_user"))
        assertTrue(verification.contains("Admin can SELECT User B profile"))
        assertTrue(verification.contains("Admin can UPDATE User B progress"))
        assertTrue(verification.contains("admin_role remains protected"))
    }

    private fun readMigration(description: String): String {
        val migrationsDir = projectRoot().resolve("supabase/migrations")
        val migration = migrationsDir.listFiles()
            ?.singleOrNull { file ->
                file.name.matches(Regex("\\d{14}_${Regex.escape(description)}\\.sql"))
            }
            ?: error("Expected exactly one timestamped migration ending in $description.sql")

        return migration.readText()
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
