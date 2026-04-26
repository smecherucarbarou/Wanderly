package com.novahorizon.wanderly.widgets

import androidx.annotation.DrawableRes
import com.novahorizon.wanderly.R

data class ResolvedStreakTier(
    val color: Int,
    val glowColor: Int,
    val label: String,
    val emoji: String,
    @param:DrawableRes @field:DrawableRes val animFile: Int
)

object StreakTierHelper {

    private val broken = tier(
        color = 0xFF6B7280.toInt(),
        label = "Broken",
        emoji = "\uD83D\uDC80",
        animFile = R.drawable.ic_streak_fire
    )
    private val starter = tier(
        color = 0xFFF97316.toInt(),
        label = "Starter",
        emoji = "\uD83D\uDD25",
        animFile = R.drawable.ic_streak_fire
    )
    private val rising = tier(
        color = 0xFFEAB308.toInt(),
        label = "Rising",
        emoji = "\uD83D\uDD25",
        animFile = R.drawable.ic_streak_fire
    )
    private val blazing = tier(
        color = 0xFFF59E0B.toInt(),
        label = "Blazing",
        emoji = "\uD83D\uDD25",
        animFile = R.drawable.ic_streak_fire_5
    )
    private val legendary = tier(
        color = 0xFFFF8A3D.toInt(),
        label = "Legendary",
        emoji = "\uD83D\uDD25",
        animFile = R.drawable.ic_streak_fire_25
    )
    private val epic = tier(
        color = 0xFFFFB347.toInt(),
        label = "Epic",
        emoji = "\uD83D\uDD25",
        animFile = R.drawable.ic_streak_fire_50
    )
    private val god = tier(
        color = 0xFFFFD166.toInt(),
        label = "GOD",
        emoji = "\uD83D\uDD25",
        animFile = R.drawable.ic_streak_fire_50
    )

    fun resolve(streakCount: Int): ResolvedStreakTier = when {
        streakCount <= 0 -> broken
        streakCount <= 6 -> starter
        streakCount <= 29 -> rising
        streakCount <= 59 -> blazing
        streakCount <= 99 -> legendary
        streakCount <= 199 -> epic
        else -> god
    }

    private fun tier(
        color: Int,
        label: String,
        emoji: String,
        @DrawableRes animFile: Int
    ): ResolvedStreakTier {
        val glowColor = (color and 0x00FFFFFF) or 0x40000000
        return ResolvedStreakTier(
            color = color,
            glowColor = glowColor,
            label = label,
            emoji = emoji,
            animFile = animFile
        )
    }
}
