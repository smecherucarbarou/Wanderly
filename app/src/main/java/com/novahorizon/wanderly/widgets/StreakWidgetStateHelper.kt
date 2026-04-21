package com.novahorizon.wanderly.widgets

import com.novahorizon.wanderly.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

enum class StreakTier {
    NONE,
    SPARK,
    BLAZE,
    INFERNO,
    LEGENDARY
}

enum class WidgetMood {
    NEUTRAL,
    PROUD,
    WATCHFUL,
    STRESSED
}

data class StreakWidgetVisualState(
    val tier: StreakTier,
    val mood: WidgetMood,
    val mascotRes: Int,
    val fireRes: Int,
    val subtitleRes: Int,
    val backgroundRes: Int,
    val countColorRes: Int,
    val subtitleColorRes: Int,
    val inDanger: Boolean
)

object StreakWidgetStateHelper {

    private val eveningDangerStartsAt: LocalTime = LocalTime.of(18, 0)

    fun resolveVisualState(
        streakCount: Int,
        lastMissionDate: String?,
        now: LocalDateTime = LocalDateTime.now()
    ): StreakWidgetVisualState {
        val tier = resolveTier(streakCount)
        val lastMission = parseDate(lastMissionDate)
        val today = now.toLocalDate()
        val mood = resolveMood(
            streakCount = streakCount,
            lastMission = lastMission,
            today = today,
            currentTime = now.toLocalTime()
        )
        val inDanger = mood == WidgetMood.STRESSED

        return StreakWidgetVisualState(
            tier = tier,
            mood = mood,
            mascotRes = resolveMascotRes(tier, mood),
            fireRes = resolveFireRes(tier),
            subtitleRes = resolveSubtitleRes(mood),
            backgroundRes = if (inDanger) R.drawable.bg_widget_streak_warning else R.drawable.bg_widget_streak,
            countColorRes = if (inDanger) R.color.primary else R.color.accent,
            subtitleColorRes = if (inDanger) R.color.accent else R.color.pollen_white,
            inDanger = inDanger
        )
    }

    fun isInDanger(lastMissionDate: String?, today: LocalDate = LocalDate.now()): Boolean {
        val parsedDate = parseDate(lastMissionDate) ?: return false
        return ChronoUnit.DAYS.between(parsedDate, today) > 1
    }

    private fun resolveTier(streakCount: Int): StreakTier = when {
        streakCount >= 50 -> StreakTier.LEGENDARY
        streakCount >= 25 -> StreakTier.INFERNO
        streakCount >= 5 -> StreakTier.BLAZE
        streakCount >= 1 -> StreakTier.SPARK
        else -> StreakTier.NONE
    }

    private fun resolveMood(
        streakCount: Int,
        lastMission: LocalDate?,
        today: LocalDate,
        currentTime: LocalTime
    ): WidgetMood {
        if (streakCount <= 0) return WidgetMood.NEUTRAL
        if (lastMission == today) return WidgetMood.PROUD

        val yesterday = today.minusDays(1)
        return when {
            lastMission == yesterday && currentTime >= eveningDangerStartsAt -> WidgetMood.STRESSED
            lastMission == yesterday -> WidgetMood.WATCHFUL
            else -> WidgetMood.NEUTRAL
        }
    }

    private fun resolveFireRes(tier: StreakTier): Int = when (tier) {
        StreakTier.LEGENDARY -> R.drawable.ic_streak_fire_50
        StreakTier.INFERNO -> R.drawable.ic_streak_fire_25
        StreakTier.BLAZE -> R.drawable.ic_streak_fire_5
        StreakTier.SPARK,
        StreakTier.NONE -> R.drawable.ic_streak_fire
    }

    private fun resolveMascotRes(tier: StreakTier, mood: WidgetMood): Int = when (mood) {
        WidgetMood.NEUTRAL -> when (tier) {
            StreakTier.NONE -> R.drawable.widget_mascot_neutral
            StreakTier.SPARK -> R.drawable.widget_mascot_spark
            StreakTier.BLAZE -> R.drawable.widget_mascot_blaze
            StreakTier.INFERNO -> R.drawable.widget_mascot_inferno
            StreakTier.LEGENDARY -> R.drawable.widget_mascot_legendary
        }

        WidgetMood.PROUD -> when (tier) {
            StreakTier.NONE -> R.drawable.widget_mascot_neutral
            StreakTier.SPARK -> R.drawable.widget_mascot_spark_proud
            StreakTier.BLAZE -> R.drawable.widget_mascot_blaze_proud
            StreakTier.INFERNO -> R.drawable.widget_mascot_inferno_proud
            StreakTier.LEGENDARY -> R.drawable.widget_mascot_legendary_proud
        }

        WidgetMood.WATCHFUL -> when (tier) {
            StreakTier.NONE -> R.drawable.widget_mascot_neutral
            StreakTier.SPARK -> R.drawable.widget_mascot_spark_watchful
            StreakTier.BLAZE -> R.drawable.widget_mascot_blaze_watchful
            StreakTier.INFERNO -> R.drawable.widget_mascot_inferno_watchful
            StreakTier.LEGENDARY -> R.drawable.widget_mascot_legendary_watchful
        }

        WidgetMood.STRESSED -> when (tier) {
            StreakTier.NONE -> R.drawable.widget_mascot_neutral
            StreakTier.SPARK -> R.drawable.widget_mascot_spark_stressed
            StreakTier.BLAZE -> R.drawable.widget_mascot_blaze_stressed
            StreakTier.INFERNO -> R.drawable.widget_mascot_inferno_stressed
            StreakTier.LEGENDARY -> R.drawable.widget_mascot_legendary_stressed
        }
    }

    private fun resolveSubtitleRes(mood: WidgetMood): Int = when (mood) {
        WidgetMood.NEUTRAL -> R.string.widget_streak_subtitle_neutral
        WidgetMood.PROUD -> R.string.widget_streak_subtitle_proud
        WidgetMood.WATCHFUL -> R.string.widget_streak_subtitle_watchful
        WidgetMood.STRESSED -> R.string.widget_streak_subtitle_stressed
    }

    private fun parseDate(rawDate: String?): LocalDate? {
        if (rawDate.isNullOrBlank()) return null
        return try {
            LocalDate.parse(rawDate)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
