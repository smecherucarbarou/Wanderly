package com.novahorizon.wanderly.ui

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.data.WanderlyRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val today = sdf.format(now.time)

        val yesterdayCal = now.clone() as Calendar
        yesterdayCal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = sdf.format(yesterdayCal.time)

        viewModelScope.launch {
            try {
                val profile = repository.getCurrentProfile() ?: return@launch
                val lastMission = profile.last_mission_date ?: ""
                val currentStreak = profile.streak_count ?: 0

                Log.d("WanderlyStreak", "Checking expiry. Today: $today, Yesterday: $yesterday, Last Mission: $lastMission")

                if (lastMission != today && lastMission != yesterday && currentStreak > 0) {
                    val cost = currentStreak * 5
                    _streakStatus.postValue(StreakStatus.Crisis(currentStreak, cost))
                } else {
                    _streakStatus.postValue(StreakStatus.Active)
                }
            } catch (e: Exception) {
                Log.e("WanderlyStreak", "Error checking streak expiry", e)
            }
        }
    }

    fun restoreStreak(cost: Int) {
        viewModelScope.launch {
            val profile = repository.getCurrentProfile() ?: return@launch
            val currentHoney = profile.honey ?: 0
            if (currentHoney >= cost) {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                val yesterday = sdf.format(calendar.time)

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

            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            val yesterday = sdf.format(calendar.time)

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
}
