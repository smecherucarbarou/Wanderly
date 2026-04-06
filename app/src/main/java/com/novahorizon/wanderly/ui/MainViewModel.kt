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
                val profile = repository.getCurrentProfile() ?: return@launch
                val lastBuzz = profile.last_buzz_date ?: ""
                
                Log.d("WanderlyStreak", "Checking expiry. Today: $today, Yesterday: $yesterday, Last Mission: $lastBuzz")

                // Dacă ultima misiune e mai veche de ieri, streak-ul s-a pierdut (reset la 0)
                if (lastBuzz != today && lastBuzz != yesterday && (profile.streak_count ?: 0) > 0) {
                    val updatedProfile = profile.copy(streak_count = 0)
                    repository.updateProfile(updatedProfile)
                    Log.d("WanderlyStreak", "Streak expired. Reset to 0.")
                }
            } catch (e: Exception) {
                Log.e("WanderlyStreak", "Error checking streak expiry", e)
            }
        }
    }
    
    fun clearStreakMessage() {
        _streakMessage.value = null
    }
}
