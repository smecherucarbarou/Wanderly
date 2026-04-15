package com.novahorizon.wanderly

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.novahorizon.wanderly.auth.AuthRouting
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.databinding.ActivityMainBinding
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.services.HiveRealtimeService
import com.novahorizon.wanderly.ui.MainViewModel
import com.novahorizon.wanderly.ui.WanderlyViewModelFactory
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { WanderlyRepository(this) }
    private val viewModel: MainViewModel by viewModels {
        WanderlyViewModelFactory(repository)
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
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            val profile = if (session != null) repository.getCurrentProfile() else null
            if (profile?.admin_role == true) {
                binding.bottomNavigation.menu.clear()
                binding.bottomNavigation.inflateMenu(R.menu.bottom_nav_menu_dev)
            }
            binding.bottomNavigation.setupWithNavController(navController)
            startHiveService(session != null)
        }

        setupObservers()
        viewModel.checkDailyStreak()
    }

    private fun startHiveService(hasSession: Boolean) {
        if (AuthRouting.shouldStartSessionServices(hasSession)) {
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
            .setTitle("STREAK CRISIS!")
            .setMessage("Did you get stuck in a tourist trap yesterday? Your $days-day streak is dead. Pay $cost Honey to buy your reputation back, or start from zero.")
            .setCancelable(false)
            .setPositiveButton("PAY THE TOLL ($cost Honey)") { _, _ ->
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
