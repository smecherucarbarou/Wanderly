package com.novahorizon.wanderly.ui.common

import androidx.annotation.StringRes
import com.novahorizon.wanderly.R

object RankUiFormatter {
    @StringRes
    fun rankNameRes(rank: Int): Int = when (rank) {
        1 -> R.string.rank_1
        2 -> R.string.rank_2
        3 -> R.string.rank_3
        else -> R.string.rank_4
    }
}
