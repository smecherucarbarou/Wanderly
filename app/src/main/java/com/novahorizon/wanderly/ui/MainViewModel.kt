package com.novahorizon.wanderly.ui

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.data.WanderlyRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainViewModel(private val repository: WanderlyRepository) : ViewModel() {

    private val _streakMessage = MutableLiveData<String?>()
    val streakMessage: LiveData<String?> = _streakMessage

    /**
     * Verifică dacă streak-ul a expirat.
     * Streak-ul expiră dacă ultima misiune nu a fost făcută nici azi, nici ieri.
     */
    sealed class StreakStatus {
        object Active : StreakStatus()
        data class Crisis(val lostStreak: Int, val cost: Int) : StreakStatus()
    }

    private val _streakStatus = MutableLiveData<StreakStatus>()
    val streakStatus: LiveData<StreakStatus> = _streakStatus

    fun checkDailyStreak() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val today = sdf.format(now.time)
        
        val yesterdayCal = now.clone() as Calendar
        yesterdayCal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = sdf.format(yesterdayCal.time)

        viewModelScope.launch {
            try {
                // Ensure we have a profile loaded
                val profile = repository.getCurrentProfile() ?: return@launch
                val lastMission = profile.last_mission_date ?: ""
                val currentStreak = profile.streak_count ?: 0
                
                Log.d("WanderlyStreak", "Checking expiry. Today: $today, Yesterday: $yesterday, Last Mission: $lastMission")

                if (lastMission != today && lastMission != yesterday && currentStreak > 0) {
                    // STREAK CRISIS TRIGGERED
                    val cost = currentStreak * 5 // 5 Honey per day of streak to restore
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
                calendar.add(Calendar.DAY_OF_YEAR, -1) // Set to yesterday so they have today to continue
                val yesterday = sdf.format(calendar.time)

                val updated = profile.copy(
                    honey = currentHoney - cost,
                    last_mission_date = yesterday
                )
                if (repository.updateProfile(updated)) {
                    _streakStatus.postValue(StreakStatus.Active)
                    _streakMessage.postValue("Streak Restored! 🍯 Your reputation is safe.")
                } else {
                    _streakMessage.postValue("Failed to restore streak. Hive connection lost.")
                }
            } else {
                _streakMessage.postValue("Not enough Honey! 🍯")
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
            calendar.add(Calendar.DAY_OF_YEAR, -1) // Set to yesterday
            val yesterday = sdf.format(calendar.time)

            val updated = profile.copy(
                streak_count = 0,
                last_mission_date = yesterday // Set to yesterday so the crisis stops, and next mission starts streak at 1
            )

            if (repository.updateProfile(updated)) {
                _streakStatus.postValue(StreakStatus.Active)
                _streakMessage.postValue("Streak reset to 0. Time to rebuild! 🐝")
            } else {
                _streakMessage.postValue("Failed to reset streak. Check connection.")
            }
        }
    }
    
    fun clearStreakMessage() {
        _streakMessage.value = null
    }
}
