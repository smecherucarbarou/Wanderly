package com.novahorizon.wanderly.widgets

import android.graphics.Color
import com.novahorizon.wanderly.R
import org.junit.Assert.assertEquals
import org.junit.Test

class StreakTierHelperTest {

    @Test
    fun resolveReturnsExpectedStyleForEveryBoundary() {
        val cases = listOf(
            0 to expectedTier("#6b7280", "Broken", "\uD83D\uDC80", R.drawable.ic_streak_fire),
            1 to expectedTier("#f97316", "Starter", "\uD83D\uDD25", R.drawable.ic_streak_fire),
            6 to expectedTier("#f97316", "Starter", "\uD83D\uDD25", R.drawable.ic_streak_fire),
            7 to expectedTier("#eab308", "Rising", "\uD83D\uDD25", R.drawable.ic_streak_fire_5),
            29 to expectedTier("#eab308", "Rising", "\uD83D\uDD25", R.drawable.ic_streak_fire_5),
            30 to expectedTier("#f59e0b", "Blazing", "\uD83D\uDD25", R.drawable.ic_streak_fire_25),
            59 to expectedTier("#f59e0b", "Blazing", "\uD83D\uDD25", R.drawable.ic_streak_fire_25),
            60 to expectedTier("#a855f7", "Legendary", "\uD83D\uDD25", R.drawable.ic_streak_fire_50),
            99 to expectedTier("#a855f7", "Legendary", "\uD83D\uDD25", R.drawable.ic_streak_fire_50),
            100 to expectedTier("#3b82f6", "Epic", "\uD83D\uDD25", R.drawable.ic_streak_fire_50),
            199 to expectedTier("#3b82f6", "Epic", "\uD83D\uDD25", R.drawable.ic_streak_fire_50),
            200 to expectedTier("#ec4899", "GOD", "\uD83D\uDD25", R.drawable.ic_streak_fire_50)
        )

        cases.forEach { (streakCount, expected) ->
            val actual = StreakTierHelper.resolve(streakCount)

            assertEquals(expected.color, actual.color)
            assertEquals(expected.glowColor, actual.glowColor)
            assertEquals(expected.label, actual.label)
            assertEquals(expected.emoji, actual.emoji)
            assertEquals(expected.animFile, actual.animFile)
        }
    }

    @Test
    fun resolveUsesTwentyFivePercentGlowAndBrokenLabelAndEmoji() {
        val tier = StreakTierHelper.resolve(0)

        assertEquals(Color.parseColor("#6b7280"), tier.color)
        assertEquals(64, Color.alpha(tier.glowColor))
        assertEquals("Broken", tier.label)
        assertEquals("\uD83D\uDC80", tier.emoji)
    }

    private fun expectedTier(
        colorHex: String,
        label: String,
        emoji: String,
        animFile: Int
    ): ExpectedTier {
        val color = Color.parseColor(colorHex)
        return ExpectedTier(
            color = color,
            glowColor = withAlpha25Percent(color),
            label = label,
            emoji = emoji,
            animFile = animFile
        )
    }

    private fun withAlpha25Percent(color: Int): Int = (0x40 shl 24) or (color and 0x00FFFFFF)

    private data class ExpectedTier(
        val color: Int,
        val glowColor: Int,
        val label: String,
        val emoji: String,
        val animFile: Int
    )
}
