package com.novahorizon.wanderly

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.novahorizon.wanderly.data.WanderlyRepository
import io.github.jan.supabase.auth.auth
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.databinding.ActivityMainBinding
import com.novahorizon.wanderly.ui.MainViewModel
import com.novahorizon.wanderly.ui.WanderlyViewModelFactory
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.services.HiveRealtimeService
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels {
        WanderlyViewModelFactory(WanderlyRepository(this))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WanderlyNotificationManager.createNotificationChannel(this)
        requestNotificationPermission()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        lifecycleScope.launch {
            // FIX BUG 1: currentUserOrNull() suspends until Auth is ready
            val user = SupabaseClient.client.auth.currentUserOrNull()
            val isDev = user?.email?.lowercase()?.trim() == "mihaileon55@gmail.com"
            if (isDev) {
                binding.bottomNavigation.menu.clear()
                binding.bottomNavigation.inflateMenu(R.menu.bottom_nav_menu_dev)
            }
            // setupWithNavController MUST be inside coroutine so menu is correct first
            binding.bottomNavigation.setupWithNavController(navController)
        }
        
        setupObservers()
        viewModel.checkDailyStreak()
        
        // BUG 1 FIXED: Start HiveRealtimeService if session is valid
        startHiveService()
    }

    private fun startHiveService() {
        val session = SupabaseClient.client.auth.currentSessionOrNull()
        if (session != null) {
            val intent = Intent(this, HiveRealtimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun setupObservers() {
        viewModel.streakMessage.observe(this) { message ->
            message?.let {
                // Assuming showSnackbar is implemented in the real app or base class
                // showSnackbar(it, isError = false)
                viewModel.clearStreakMessage()
            }
        }

        viewModel.streakStatus.observe(this) { status ->
            if (status is MainViewModel.StreakStatus.Crisis) {
                showStreakCrisisDialog(status.lostStreak, status.cost)
            }
        }
    }

    private fun showStreakCrisisDialog(days: Int, cost: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Wanderly_AlertDialog)
            .setTitle("STREAK CRISIS! ⚠️")
            .setMessage("Did you get stuck in a tourist trap yesterday? Your $days-day streak is dead. Pay $cost Honey to buy your reputation back, or start from zero.")
            .setCancelable(false)
            .setPositiveButton("PAY THE TOLL ($cost 🍯)") { _, _ ->
                viewModel.restoreStreak(cost)
            }
            .setNegativeButton("ACCEPT DEFEAT") { _, _ ->
                viewModel.acceptStreakLoss()
            }
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
