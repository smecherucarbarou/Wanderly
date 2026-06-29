package com.novahorizon.wanderly.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the privacy invariant (audit H-10 / QW-6): the column list used to SELECT a profile must
 * never include server-owned/sensitive columns, so a client query can't pull another user's
 * coordinates or admin flag into memory.
 */
class ProfileVisibleColumnsTest {

    @Test
    fun `excludes sensitive server-owned columns`() {
        val forbidden = listOf(
            "last_lat",
            "last_lng",
            "admin_role",
            "last_mission_date",
            "last_buzz_date",
            "updated_at"
        )
        forbidden.forEach { column ->
            assertFalse(
                "PROFILE_VISIBLE_COLUMNS must not expose '$column'",
                PROFILE_VISIBLE_COLUMNS.contains(column)
            )
        }
    }

    @Test
    fun `includes the expected safe columns`() {
        listOf("id", "username", "honey", "friend_code", "streak_count").forEach { column ->
            assertTrue("PROFILE_VISIBLE_COLUMNS should expose '$column'", PROFILE_VISIBLE_COLUMNS.contains(column))
        }
    }
}
