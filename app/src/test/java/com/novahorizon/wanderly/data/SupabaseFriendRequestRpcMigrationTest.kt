package com.novahorizon.wanderly.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseFriendRequestRpcMigrationTest {

    @Test
    fun `friend request lifecycle rpcs are scoped to authenticated recipient`() {
        val sql = readSupabaseSql()

        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION public.get_pending_friend_request_profiles()"))
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION public.accept_friend_request(p_requester_id uuid)"))
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION public.reject_friend_request(p_requester_id uuid)"))

        val acceptFunction = sql.substringAfter("CREATE OR REPLACE FUNCTION public.accept_friend_request")
            .substringBefore("CREATE OR REPLACE FUNCTION public.reject_friend_request")
        assertTrue(acceptFunction.contains("v_user_id uuid := auth.uid()"))
        assertTrue(acceptFunction.contains("f.user_id = p_requester_id"))
        assertTrue(acceptFunction.contains("f.friend_id = v_user_id"))
        assertTrue(acceptFunction.contains("f.status = 'pending'"))
        assertTrue(acceptFunction.contains("status = 'accepted'"))

        val rejectFunction = sql.substringAfter("CREATE OR REPLACE FUNCTION public.reject_friend_request")
        assertTrue(rejectFunction.contains("v_user_id uuid := auth.uid()"))
        assertTrue(rejectFunction.contains("f.user_id = p_requester_id"))
        assertTrue(rejectFunction.contains("f.friend_id = v_user_id"))
        assertTrue(rejectFunction.contains("f.status = 'pending'"))
        assertTrue(rejectFunction.contains("DELETE FROM public.friendships"))

        assertTrue(sql.contains("REVOKE ALL ON FUNCTION public.get_pending_friend_request_profiles() FROM PUBLIC"))
        assertTrue(sql.contains("REVOKE ALL ON FUNCTION public.accept_friend_request(uuid) FROM PUBLIC"))
        assertTrue(sql.contains("REVOKE ALL ON FUNCTION public.reject_friend_request(uuid) FROM PUBLIC"))
        assertTrue(sql.contains("GRANT EXECUTE ON FUNCTION public.get_pending_friend_request_profiles() TO authenticated"))
        assertTrue(sql.contains("GRANT EXECUTE ON FUNCTION public.accept_friend_request(uuid) TO authenticated"))
        assertTrue(sql.contains("GRANT EXECUTE ON FUNCTION public.reject_friend_request(uuid) TO authenticated"))
    }

    private fun readSupabaseSql(): String {
        val root = projectRoot().resolve("supabase")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "sql" }
            .joinToString(separator = "\n") { it.readText() }
    }

    private fun projectRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        return generateSequence(File(userDir)) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not find project root")
    }
}
