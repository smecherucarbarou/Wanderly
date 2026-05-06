package com.novahorizon.wanderly.ui.common

import com.novahorizon.wanderly.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RankUiFormatterTest {

    @Test
    fun `rankNameRes maps rank buckets to string resources`() {
        assertEquals(R.string.rank_1, RankUiFormatter.rankNameRes(1))
        assertEquals(R.string.rank_2, RankUiFormatter.rankNameRes(2))
        assertEquals(R.string.rank_3, RankUiFormatter.rankNameRes(3))
        assertEquals(R.string.rank_4, RankUiFormatter.rankNameRes(4))
        assertEquals(R.string.rank_4, RankUiFormatter.rankNameRes(99))
        assertEquals(R.string.rank_4, RankUiFormatter.rankNameRes(0))
    }
}
