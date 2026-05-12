package com.novahorizon.wanderly.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialRepositoryLeaderboardTest {

    @Test
    fun `leaderboard params always include bounded max rows`() {
        assertEquals(SocialRepository.LeaderboardParams(max_rows = 1), SocialRepository.buildLeaderboardParams(-10))
        assertEquals(SocialRepository.LeaderboardParams(max_rows = 50), SocialRepository.buildLeaderboardParams(50))
        assertEquals(SocialRepository.LeaderboardParams(max_rows = 100), SocialRepository.buildLeaderboardParams(250))
    }

    @Test
    fun `default leaderboard params request fifty rows`() {
        assertEquals(SocialRepository.LeaderboardParams(max_rows = 50), SocialRepository.buildLeaderboardParams())
    }
}
