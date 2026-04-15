package com.novahorizon.wanderly.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HiveRankTest {

    @Test
    fun derivesRankFromHoneyThresholds() {
        assertEquals(1, HiveRank.fromHoney(0))
        assertEquals(1, HiveRank.fromHoney(99))
        assertEquals(2, HiveRank.fromHoney(100))
        assertEquals(3, HiveRank.fromHoney(300))
        assertEquals(4, HiveRank.fromHoney(600))
    }

    @Test
    fun updatesProfileRankFromHoney() {
        val profile = Profile(id = "user-1", honey = 320, hive_rank = 1)

        val normalized = profile.withDerivedHiveRank()

        assertEquals(3, normalized.hive_rank)
    }

    @Test
    fun exposesMissionRadiusPerRank() {
        assertEquals(800, HiveRank.missionRadiusMeters(1))
        assertEquals(1500, HiveRank.missionRadiusMeters(2))
        assertEquals(3000, HiveRank.missionRadiusMeters(3))
        assertEquals(6000, HiveRank.missionRadiusMeters(4))
    }
}
