package com.novahorizon.wanderly.widgets

import android.graphics.Color
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import com.novahorizon.wanderly.R

data class StreakTierStyle(
    val color: Int,
    val glowColor: Int,
    val label: String,
    val emoji: String,
    @DrawableRes val animFile: Int
)

object StreakTierHelper {

    private const val GLOW_ALPHA: Int = 0x40
    private const val BROKEN_EMOJI: String = "\uD83D\uDC80"
    private const val FIRE_EMOJI: String = "\uD83D\uDD25"

    fun resolve(streakCount: Int): StreakTierStyle {
        val tier = when {
            streakCount >= 200 -> TierDefinition(
                color = "#ec4899",
                label = "GOD",
                emoji = FIRE_EMOJI,
                animFile = R.drawable.ic_streak_fire_50
            )
            streakCount >= 100 -> TierDefinition(
                color = "#3b82f6",
                label = "Epic",
                emoji = FIRE_EMOJI,
                animFile = R.drawable.ic_streak_fire_50
            )
            streakCount >= 60 -> TierDefinition(
                color = "#a855f7",
                label = "Legendary",
                emoji = FIRE_EMOJI,
                animFile = R.drawable.ic_streak_fire_50
            )
            streakCount >= 30 -> TierDefinition(
                color = "#f59e0b",
                label = "Blazing",
                emoji = FIRE_EMOJI,
                animFile = R.drawable.ic_streak_fire_25
            )
            streakCount >= 7 -> TierDefinition(
                color = "#eab308",
                label = "Rising",
                emoji = FIRE_EMOJI,
                animFile = R.drawable.ic_streak_fire_5
            )
            streakCount >= 1 -> TierDefinition(
                color = "#f97316",
                label = "Starter",
                emoji = FIRE_EMOJI,
                animFile = R.drawable.ic_streak_fire
            )
            else -> TierDefinition(
                color = "#6b7280",
                label = "Broken",
                emoji = BROKEN_EMOJI,
                animFile = R.drawable.ic_streak_fire
            )
        }

        val color = Color.parseColor(tier.color)
        return StreakTierStyle(
            color = color,
            glowColor = ColorUtils.setAlphaComponent(color, GLOW_ALPHA),
            label = tier.label,
            emoji = tier.emoji,
            animFile = tier.animFile
        )
    }

    private data class TierDefinition(
        val color: String,
        val label: String,
        val emoji: String,
        val animFile: Int
    )

}
