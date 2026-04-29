package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseEdgeQuotaMigrationTest {

    @Test
    fun `edge quota migration adds atomic per user provider quota rpc`() {
        val migration = readMigration("add_edge_api_quotas")

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS public.api_usage_limits"))
        assertTrue(migration.contains("user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE"))
        assertTrue(migration.contains("provider text NOT NULL CHECK"))
        assertTrue(migration.contains("PRIMARY KEY (user_id, provider, window_start)"))
        assertTrue(migration.contains("CREATE OR REPLACE FUNCTION public.consume_api_quota"))
        assertTrue(migration.contains("auth.uid()"))
        assertTrue(migration.contains("ON CONFLICT"))
        assertTrue(migration.contains("request_count < max_requests_per_day"))
        assertTrue(migration.contains("GRANT EXECUTE ON FUNCTION public.consume_api_quota"))
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
