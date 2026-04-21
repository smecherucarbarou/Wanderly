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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import com.novahorizon.wanderly.auth.AuthRouting
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.databinding.ActivityMainBinding
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.services.HiveRealtimeService
import com.novahorizon.wanderly.ui.common.WanderlyViewModelFactory
import com.novahorizon.wanderly.ui.main.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { WanderlyGraph.repository(this) }
    private val viewModel: MainViewModel by viewModels {
        WanderlyViewModelFactory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        WanderlyNotificationManager.createNotificationChannel(this)
        requestNotificationPermission()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        lifecycleScope.launch {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
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

    private fun stopHiveService() {
        stopService(Intent(this, HiveRealtimeService::class.java))
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.navHostFragment.setPadding(
                binding.navHostFragment.paddingLeft,
                systemBars.top,
                binding.navHostFragment.paddingRight,
                0
            )
            binding.bottomNavigation.setPadding(
                binding.bottomNavigation.paddingLeft,
                binding.bottomNavigation.paddingTop,
                binding.bottomNavigation.paddingRight,
                systemBars.bottom
            )
            insets
        }
    }

    private fun setupObservers() {
        viewModel.streakMessage.observe(this) { message ->
            message?.let {
                Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
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
            .setTitle(R.string.streak_crisis_title)
            .setMessage(getString(R.string.streak_crisis_message, days, cost))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.streak_crisis_pay, cost)) { _, _ ->
                viewModel.restoreStreak(cost)
            }
            .setNegativeButton(R.string.streak_crisis_accept_defeat) { _, _ ->
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

    override fun onDestroy() {
        if (isFinishing) {
            stopHiveService()
        }
        super.onDestroy()
    }
}
