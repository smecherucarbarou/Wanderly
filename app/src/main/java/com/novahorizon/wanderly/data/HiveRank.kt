package com.novahorizon.wanderly.data

object HiveRank {
    fun fromHoney(honey: Int?): Int {
        val totalHoney = honey ?: 0
        return when {
            totalHoney >= 600 -> 4
            totalHoney >= 300 -> 3
            totalHoney >= 100 -> 2
            else -> 1
        }
    }

    fun minHoneyForRank(rank: Int): Int = when (rank) {
        1 -> 0
        2 -> 100
        3 -> 300
        4 -> 600
        else -> 1000
    }

    fun maxHoneyForRank(rank: Int): Int = when (rank) {
        1 -> 99
        2 -> 299
        3 -> 599
        else -> 600
    }

    fun missionRadiusMeters(rank: Int): Int = when (rank) {
        1 -> 800
        2 -> 1500
        3 -> 3000
        else -> 6000
    }
}

fun Profile.derivedHiveRank(): Int = HiveRank.fromHoney(honey)

fun Profile.withDerivedHiveRank(): Profile {
    val derivedRank = derivedHiveRank()
    return if (hive_rank == derivedRank) this else copy(hive_rank = derivedRank)
}
