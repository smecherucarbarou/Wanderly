package com.novahorizon.wanderly.ui.main

import com.novahorizon.wanderly.observability.AppLogger

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.observability.CrashEvent
import com.novahorizon.wanderly.observability.CrashKey
import com.novahorizon.wanderly.observability.CrashReporter
import com.novahorizon.wanderly.streak.DailyStreakStatus
import com.novahorizon.wanderly.streak.DailyStreakStatusEvaluator
import com.novahorizon.wanderly.util.DateUtils
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainViewModel(private val repository: WanderlyRepository) : ViewModel() {

    private val _streakMessage = MutableLiveData<String?>()
    val streakMessage: LiveData<String?> = _streakMessage

    /**
     * Checks whether the streak has expired.
     * A streak expires when the last mission was completed on neither today nor yesterday.
     */
    sealed class StreakStatus {
        object Active : StreakStatus()
        data class Crisis(val lostStreak: Int, val cost: Int) : StreakStatus()
    }

    private val _streakStatus = MutableLiveData<StreakStatus>()
    val streakStatus: LiveData<StreakStatus> = _streakStatus

    fun checkDailyStreak() {
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val today = DateUtils.formatUtcDate(now.time)

        viewModelScope.launch {
            try {
                val profile = repository.getCurrentProfile() ?: return@launch
                val currentStreak = profile.streak_count ?: 0
                val streakStatus = DailyStreakStatusEvaluator.evaluate(
                    streakCount = currentStreak,
                    lastMissionDate = profile.last_mission_date,
                    today = java.time.LocalDate.parse(today)
                )

                when (streakStatus) {
                    DailyStreakStatus.FREEZE_ELIGIBLE -> {
                        val cost = currentStreak * 5
                        _streakStatus.postValue(StreakStatus.Crisis(currentStreak, cost))
                    }

                    DailyStreakStatus.HARD_LOST -> {
                        val yesterday = yesterdayUtcDate()
                        val updated = profile.copy(
                            streak_count = 0,
                            last_mission_date = yesterday
                        )
                        if (repository.updateProfile(updated)) {
                            _streakStatus.postValue(StreakStatus.Active)
                            _streakMessage.postValue(
                                "Streak lost for good. Freeze only saves one missed day."
                            )
                        } else {
                            _streakMessage.postValue("Failed to reset lost streak. Check connection.")
                        }
                    }

                    else -> _streakStatus.postValue(StreakStatus.Active)
                }
            } catch (e: Exception) {
                CrashReporter.recordNonFatal(
                    CrashEvent.STREAK_CHECK_FAILED,
                    e,
                    CrashKey.COMPONENT to "main",
                    CrashKey.OPERATION to "daily_streak_check"
                )
                if (BuildConfig.DEBUG) {
                    AppLogger.e("WanderlyStreak", "Error checking streak expiry", e)
                } else {
                    AppLogger.e("WanderlyStreak", "Error checking streak expiry")
                }
            }
        }
    }

    fun restoreStreak(cost: Int) {
        viewModelScope.launch {
            val profile = repository.getCurrentProfile() ?: return@launch
            val currentHoney = profile.honey ?: 0
            val streakStatus = DailyStreakStatusEvaluator.evaluate(
                streakCount = profile.streak_count ?: 0,
                lastMissionDate = profile.last_mission_date,
                today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
            )
            if (streakStatus != DailyStreakStatus.FREEZE_ELIGIBLE) {
                _streakMessage.postValue("Freeze only saves exactly one missed day.")
                return@launch
            }
            if (currentHoney >= cost) {
                val yesterday = yesterdayUtcDate()

                val updated = profile.copy(
                    honey = currentHoney - cost,
                    last_mission_date = yesterday
                )
                if (repository.updateProfile(updated)) {
                    _streakStatus.postValue(StreakStatus.Active)
                    _streakMessage.postValue("Streak restored! Your reputation is safe.")
                } else {
                    _streakMessage.postValue("Failed to restore streak. Hive connection lost.")
                }
            } else {
                _streakMessage.postValue("Not enough Honey.")
            }
        }
    }

    fun acceptStreakLoss() {
        viewModelScope.launch {
            val profile = repository.getCurrentProfile() ?: return@launch

            val yesterday = yesterdayUtcDate()

            val updated = profile.copy(
                streak_count = 0,
                last_mission_date = yesterday
            )

            if (repository.updateProfile(updated)) {
                _streakStatus.postValue(StreakStatus.Active)
                _streakMessage.postValue("Streak reset to 0. Time to rebuild!")
            } else {
                _streakMessage.postValue("Failed to reset streak. Check connection.")
            }
        }
    }

    fun clearStreakMessage() {
        _streakMessage.value = null
    }

    private fun yesterdayUtcDate(): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        return DateUtils.formatUtcDate(calendar.time)
    }
}
