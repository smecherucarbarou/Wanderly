package com.novahorizon.wanderly

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import com.novahorizon.wanderly.auth.AuthRouting
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.databinding.ActivityMainBinding
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.services.HiveRealtimeService
import com.novahorizon.wanderly.ui.main.MainViewModel
import com.novahorizon.wanderly.ui.MainNavigationDestinations
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    @Inject
    lateinit var repository: WanderlyRepository
    private var shouldRoutePendingInvite = false
    private var bottomNavigationReady = false
    private val viewModel: MainViewModel by viewModels()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        WanderlyNotificationManager.createNotificationChannel(this)

        lifecycleScope.launch {
            val onboardingSeen = withContext(Dispatchers.IO) {
                repository.isOnboardingSeenSuspend()
            }
            val hasPendingInvite = withContext(Dispatchers.IO) {
                !repository.peekPendingInviteCode().isNullOrBlank()
            }
            setupNavGraph(onboardingSeen, hasPendingInvite)
        }

        setupObservers()
        viewModel.checkDailyStreak()
    }

    private fun setupNavGraph(onboardingSeen: Boolean, hasPendingInvite: Boolean) {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph).apply {
            setStartDestination(
                MainNavigationDestinations.initialStartDestination(
                    onboardingSeen = onboardingSeen,
                    mapDestinationId = R.id.mapFragment,
                    onboardingDestinationId = R.id.onboardingFragment
                )
            )
        }
        shouldRoutePendingInvite = hasPendingInvite
        navController.setGraph(navGraph, null)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.bottomNavigation.visibility =
                if (
                    MainNavigationDestinations.shouldShowBottomNavigation(
                        currentDestinationId = destination.id,
                        onboardingDestinationId = R.id.onboardingFragment
                    )
                ) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            routePendingInviteIfNeeded(destination.id)
        }

        lifecycleScope.launch {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            binding.bottomNavigation.setupWithNavController(navController)
            bottomNavigationReady = true
            routePendingInviteIfNeeded(navController.currentDestination?.id)
            startHiveService(session != null)
        }
    }

    private fun startHiveService(hasSession: Boolean) {
        if (AuthRouting.shouldStartSessionServices(hasSession)) {
            val intent = Intent(this, HiveRealtimeService::class.java)
            startForegroundService(intent)
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
            .setMessage(resources.getQuantityString(R.plurals.streak_crisis_message, days, days, cost))
            .setCancelable(false)
            .setPositiveButton(resources.getQuantityString(R.plurals.streak_crisis_pay, cost, cost)) { _, _ ->
                viewModel.restoreStreak(cost)
            }
            .setNegativeButton(R.string.streak_crisis_accept_defeat) { _, _ ->
                viewModel.acceptStreakLoss()
            }
            .show()
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        val permissionPrefs = getSharedPreferences(NOTIFICATION_PERMISSION_PREFS, MODE_PRIVATE)
        val requestedBefore = permissionPrefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        )

        when {
            shouldShowRationale -> showNotificationPermissionRationale()
            requestedBefore -> showNotificationPermissionSettingsDialog()
            else -> launchNotificationPermissionRequest()
        }
    }

    private fun showNotificationPermissionRationale() {
        AlertDialog.Builder(this, R.style.Wanderly_AlertDialog)
            .setTitle(R.string.notification_permission_rationale_title)
            .setMessage(R.string.notification_permission_rationale_message)
            .setPositiveButton(R.string.notification_permission_rationale_positive) { _, _ ->
                launchNotificationPermissionRequest()
            }
            .setNegativeButton(R.string.notification_permission_negative, null)
            .show()
    }

    private fun showNotificationPermissionSettingsDialog() {
        AlertDialog.Builder(this, R.style.Wanderly_AlertDialog)
            .setTitle(R.string.notification_permission_settings_title)
            .setMessage(R.string.notification_permission_settings_message)
            .setPositiveButton(R.string.notification_permission_settings_positive) { _, _ ->
                openNotificationSettings()
            }
            .setNegativeButton(R.string.notification_permission_negative, null)
            .show()
    }

    private fun launchNotificationPermissionRequest() {
        getSharedPreferences(NOTIFICATION_PERMISSION_PREFS, MODE_PRIVATE)
            .edit {
                putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        )
    }

    private fun routePendingInviteIfNeeded(destinationId: Int?) {
        if (!shouldRoutePendingInvite || !bottomNavigationReady) return
        if (destinationId == null || destinationId == R.id.onboardingFragment) return

        shouldRoutePendingInvite = false
        if (destinationId == R.id.socialFragment) return

        binding.bottomNavigation.post {
            binding.bottomNavigation.selectedItemId = R.id.socialFragment
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopHiveService()
        }
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_PREFS = "wanderly_runtime_permissions"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "post_notifications_requested"
    }
}
