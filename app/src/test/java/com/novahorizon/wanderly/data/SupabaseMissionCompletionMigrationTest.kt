package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseMissionCompletionMigrationTest {

    @Test
    fun `mission completion migration makes rewards server authoritative`() {
        val migration = readMigration("complete_mission_reward_integrity")

        assertTrue(migration.contains("CREATE OR REPLACE FUNCTION public.complete_mission()"))
        assertTrue(migration.contains("auth.uid()"))
        assertTrue(migration.contains("FOR UPDATE"))
        assertTrue(migration.contains("duplicate"))
        assertTrue(migration.contains("last_mission_date"))
        assertTrue(migration.contains("honey"))
        assertTrue(migration.contains("streak_count"))
        assertTrue(migration.contains("REVOKE UPDATE ON public.profiles FROM authenticated"))
        assertTrue(migration.contains("GRANT UPDATE (username, badges, cities_visited, avatar_url, friend_code, explorer_class)"))
        assertTrue(migration.contains("GRANT EXECUTE ON FUNCTION public.complete_mission() TO authenticated"))
        assertFalse(migration.contains("p_user_id"))
        assertFalse(migration.contains("user_id uuid"))
    }

    @Test
    fun `mission completion migration moves other sensitive profile writes behind rpcs`() {
        val migration = readMigration("complete_mission_reward_integrity")

        assertTrue(migration.contains("CREATE OR REPLACE FUNCTION public.update_profile_location"))
        assertTrue(migration.contains("CREATE OR REPLACE FUNCTION public.accept_streak_loss"))
        assertTrue(migration.contains("CREATE OR REPLACE FUNCTION public.restore_streak"))
        assertTrue(migration.contains("GRANT EXECUTE ON FUNCTION public.update_profile_location"))
        assertTrue(migration.contains("GRANT EXECUTE ON FUNCTION public.accept_streak_loss"))
        assertTrue(migration.contains("GRANT EXECUTE ON FUNCTION public.restore_streak"))
    }

    @Test
    fun `mission reward integrity verification script covers required backend cases`() {
        val verification = projectRoot().resolve("supabase/tests/mission_reward_integrity.sql").readText()

        assertTrue(verification.contains("Unauthenticated completion rejected"))
        assertTrue(verification.contains("User A cannot complete User B mission"))
        assertTrue(verification.contains("Valid completion succeeds once"))
        assertTrue(verification.contains("Duplicate completion does not double-award"))
        assertTrue(verification.contains("Direct PATCH honey/streak_count fails"))
        assertTrue(verification.contains("complete_mission does not accept user_id"))
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

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }
}
