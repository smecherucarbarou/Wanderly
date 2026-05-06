package com.novahorizon.wanderly.ui.compose.util

import android.content.Context
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.HiveRank
import com.novahorizon.wanderly.ui.common.UiText

fun rankDisplayName(rank: Int): String = when (rank) {
    1 -> "Larva"
    2 -> "Worker Bee"
    3 -> "Scout Bee"
    else -> "Queen Bee"
}

fun rankProgress(honey: Int, rank: Int): Float {
    val min = HiveRank.minHoneyForRank(rank)
    val max = HiveRank.minHoneyForRank(rank + 1)
    return if (max > min) {
        ((honey - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    } else 1f
}

fun uiTextToString(uiText: UiText, context: Context): String {
    return uiText.asString(context)
}
