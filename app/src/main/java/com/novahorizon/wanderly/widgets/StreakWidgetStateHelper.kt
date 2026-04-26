package com.novahorizon.wanderly.widgets

import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.WidgetStreakSnapshot
import com.novahorizon.wanderly.streak.DailyStreakStatus
import com.novahorizon.wanderly.streak.DailyStreakStatusEvaluator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

enum class WidgetMood {
    NEUTRAL,
    PROUD,
    WATCHFUL,
    STRESSED
}

data class StreakWidgetVisualState(
    val tier: ResolvedStreakTier,
    val mood: WidgetMood,
    val mascotRes: Int,
    val fireRes: Int,
    val subtitleRes: Int,
    val backgroundRes: Int,
    val countColorRes: Int,
    val subtitleColorRes: Int,
    val inDanger: Boolean,
    val showStaleIndicator: Boolean,
    val message: String?,
    val frameIndex: Int
)

object StreakWidgetStateHelper {

    private val eveningDangerStartsAt: LocalTime = LocalTime.of(18, 0)
    private const val STALE_AFTER_MILLIS = 5 * 60 * 1000L
    private const val FRAME_COUNT = 3

    fun resolveVisualState(
        streakCount: Int,
        lastMissionDate: String?,
        now: LocalDateTime = LocalDateTime.now()
    ): StreakWidgetVisualState = resolveVisualState(
        streakCount = streakCount,
        lastMissionDate = lastMissionDate,
        now = now,
        showStaleIndicator = false
    )

    fun resolveVisualState(
        snapshot: WidgetStreakSnapshot?,
        currentFetchSucceeded: Boolean,
        now: LocalDateTime = LocalDateTime.now()
    ): StreakWidgetVisualState {
        val showStaleIndicator = snapshot != null &&
            !currentFetchSucceeded &&
            now.toEpochMillis() - snapshot.savedAtMillis > STALE_AFTER_MILLIS

        return resolveVisualState(
            streakCount = snapshot?.streakCount ?: 0,
            lastMissionDate = snapshot?.lastMissionDate,
            now = now,
            showStaleIndicator = showStaleIndicator
        )
    }

    fun resolveFrameIndex(tickMillis: Long): Int {
        return ((tickMillis / StreakWidgetAlarmScheduler.REFRESH_INTERVAL_MILLIS) % FRAME_COUNT)
            .toInt()
    }

    fun isInDanger(lastMissionDate: String?, today: LocalDate = LocalDate.now()): Boolean {
        return DailyStreakStatusEvaluator.evaluate(
            streakCount = 1,
            lastMissionDate = lastMissionDate,
            today = today
        ) == DailyStreakStatus.FREEZE_ELIGIBLE
    }

    private fun resolveVisualState(
        streakCount: Int,
        lastMissionDate: String?,
        now: LocalDateTime,
        showStaleIndicator: Boolean
    ): StreakWidgetVisualState {
        val today = now.toLocalDate()
        val streakStatus = DailyStreakStatusEvaluator.evaluate(
            streakCount = streakCount,
            lastMissionDate = lastMissionDate,
            today = today
        )
        val tier = if (streakStatus == DailyStreakStatus.HARD_LOST) {
            StreakTierHelper.resolve(0)
        } else {
            StreakTierHelper.resolve(streakCount)
        }
        val mood = resolveMood(
            streakStatus = streakStatus,
            currentTime = now.toLocalTime()
        )
        val inDanger = mood == WidgetMood.STRESSED

        return StreakWidgetVisualState(
            tier = tier,
            mood = mood,
            mascotRes = resolveMascotRes(tier, mood),
            fireRes = tier.animFile,
            subtitleRes = resolveSubtitleRes(mood),
            backgroundRes = if (inDanger) {
                R.drawable.bg_widget_streak_warning
            } else {
                R.drawable.bg_widget_streak
            },
            countColorRes = if (inDanger) R.color.primary else R.color.accent,
            subtitleColorRes = if (inDanger) R.color.accent else R.color.pollen_white,
            inDanger = inDanger,
            showStaleIndicator = showStaleIndicator,
            message = resolveMessage(streakCount),
            frameIndex = resolveFrameIndex(now.toEpochMillis())
        )
    }

    private fun resolveMood(
        streakStatus: DailyStreakStatus,
        currentTime: LocalTime
    ): WidgetMood {
        return when (streakStatus) {
            DailyStreakStatus.ACTIVE_TODAY -> WidgetMood.PROUD
            DailyStreakStatus.AT_RISK ->
                if (currentTime >= eveningDangerStartsAt) WidgetMood.STRESSED else WidgetMood.WATCHFUL
            DailyStreakStatus.FREEZE_ELIGIBLE -> WidgetMood.STRESSED
            DailyStreakStatus.HARD_LOST,
            DailyStreakStatus.INACTIVE -> WidgetMood.NEUTRAL
        }
    }

    private fun resolveMascotRes(tier: ResolvedStreakTier, mood: WidgetMood): Int = when (mood) {
        WidgetMood.NEUTRAL -> when (tier.label) {
            "Broken" -> R.drawable.widget_mascot_neutral
            "Starter" -> R.drawable.widget_mascot_spark
            "Rising",
            "Blazing" -> R.drawable.widget_mascot_blaze
            "Legendary",
            "Epic",
            "GOD" -> R.drawable.widget_mascot_legendary
            else -> R.drawable.widget_mascot_neutral
        }

        WidgetMood.PROUD -> when (tier.label) {
            "Broken" -> R.drawable.widget_mascot_neutral
            "Starter" -> R.drawable.widget_mascot_spark_proud
            "Rising",
            "Blazing" -> R.drawable.widget_mascot_blaze_proud
            "Legendary",
            "Epic",
            "GOD" -> R.drawable.widget_mascot_legendary_proud
            else -> R.drawable.widget_mascot_neutral
        }

        WidgetMood.WATCHFUL -> when (tier.label) {
            "Broken" -> R.drawable.widget_mascot_neutral
            "Starter" -> R.drawable.widget_mascot_spark_watchful
            "Rising",
            "Blazing" -> R.drawable.widget_mascot_blaze_watchful
            "Legendary",
            "Epic",
            "GOD" -> R.drawable.widget_mascot_legendary_watchful
            else -> R.drawable.widget_mascot_neutral
        }

        WidgetMood.STRESSED -> when (tier.label) {
            "Broken" -> R.drawable.widget_mascot_neutral
            "Starter" -> R.drawable.widget_mascot_spark_stressed
            "Rising",
            "Blazing" -> R.drawable.widget_mascot_blaze_stressed
            "Legendary",
            "Epic",
            "GOD" -> R.drawable.widget_mascot_legendary_stressed
            else -> R.drawable.widget_mascot_neutral
        }
    }

    private fun resolveSubtitleRes(mood: WidgetMood): Int = when (mood) {
        WidgetMood.NEUTRAL -> R.string.widget_streak_subtitle_neutral
        WidgetMood.PROUD -> R.string.widget_streak_subtitle_proud
        WidgetMood.WATCHFUL -> R.string.widget_streak_subtitle_watchful
        WidgetMood.STRESSED -> R.string.widget_streak_subtitle_stressed
    }

    private fun resolveMessage(streakCount: Int): String? {
        return if (streakCount <= 0) "Start again today." else null
    }

    private fun parseDate(rawDate: String?): LocalDate? {
        if (rawDate.isNullOrBlank()) return null
        return try {
            LocalDate.parse(rawDate)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun LocalDateTime.toEpochMillis(): Long {
        return atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
