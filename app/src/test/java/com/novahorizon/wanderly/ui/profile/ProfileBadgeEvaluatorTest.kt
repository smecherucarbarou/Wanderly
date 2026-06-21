package com.novahorizon.wanderly.ui.profile

import com.novahorizon.wanderly.data.Profile
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBadgeEvaluatorTest {

    @Test
    fun `unlocked badges use canonical ids consumed by compose badge UI`() {
        val updatedProfile = ProfileBadgeEvaluator.updatedProfileWithUnlockedBadges(
            Profile(
                id = "user-1",
                honey = 1_100,
                streak_count = 7,
                badges = emptyList()
            )
        )

        val badges = updatedProfile.badges.orEmpty().toSet()
        assertTrue(badges.contains("first_flight"))
        assertTrue(badges.contains("7-day_streak"))
        assertTrue(badges.contains("queen_bee"))
    }
}
