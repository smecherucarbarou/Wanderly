package com.novahorizon.wanderly.widgets

import com.novahorizon.wanderly.R
import org.junit.Assert.assertEquals
import org.junit.Test

class StreakTierHelperTest {

    @Test
    fun resolvesAllTierBoundaries() {
        assertTier(
            streakCount = 0,
            label = "Broken",
            emoji = "\uD83D\uDC80",
            color = 0xFF6B7280.toInt(),
            animFile = R.drawable.ic_streak_fire
        )
        assertTier(1, "Starter", "\uD83D\uDD25", 0xFFF97316.toInt(), R.drawable.ic_streak_fire)
        assertTier(6, "Starter", "\uD83D\uDD25", 0xFFF97316.toInt(), R.drawable.ic_streak_fire)
        assertTier(7, "Rising", "\uD83D\uDD25", 0xFFEAB308.toInt(), R.drawable.ic_streak_fire)
        assertTier(29, "Rising", "\uD83D\uDD25", 0xFFEAB308.toInt(), R.drawable.ic_streak_fire)
        assertTier(30, "Blazing", "\uD83D\uDD25", 0xFFF59E0B.toInt(), R.drawable.ic_streak_fire_5)
        assertTier(59, "Blazing", "\uD83D\uDD25", 0xFFF59E0B.toInt(), R.drawable.ic_streak_fire_5)
        assertTier(60, "Legendary", "\uD83D\uDC9C", 0xFFA855F7.toInt(), R.drawable.ic_streak_fire_25)
        assertTier(99, "Legendary", "\uD83D\uDC9C", 0xFFA855F7.toInt(), R.drawable.ic_streak_fire_25)
        assertTier(100, "Epic", "\uD83D\uDD25", 0xFF3B82F6.toInt(), R.drawable.ic_streak_fire_50)
        assertTier(199, "Epic", "\uD83D\uDD25", 0xFF3B82F6.toInt(), R.drawable.ic_streak_fire_50)
        assertTier(200, "GOD", "\uD83D\uDD25", 0xFFEC4899.toInt(), R.drawable.ic_streak_fire_50)
    }

    @Test
    fun glowColorUsesQuarterAlphaOfBaseColor() {
        val blazing = StreakTierHelper.resolve(58)

        assertEquals(0x40, blazing.glowColor ushr 24)
        assertEquals(0xFFF59E0B.toInt(), blazing.color)
        assertEquals(0x40F59E0B, blazing.glowColor)
    }

    private fun assertTier(
        streakCount: Int,
        label: String,
        emoji: String,
        color: Int,
        animFile: Int
    ) {
        val tier = StreakTierHelper.resolve(streakCount)

        assertEquals(label, tier.label)
        assertEquals(emoji, tier.emoji)
        assertEquals(color, tier.color)
        assertEquals(animFile, tier.animFile)
    }
}
