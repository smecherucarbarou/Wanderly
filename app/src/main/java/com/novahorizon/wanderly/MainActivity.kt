package com.novahorizon.wanderly

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import com.novahorizon.wanderly.auth.AuthRouting
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.notifications.NotificationPermissionManager
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

    private lateinit var navHostFragment: FragmentContainerView
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var rootView: LinearLayout

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
        enableEdgeToEdge()

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        navHostFragment = FragmentContainerView(this).apply {
            id = R.id.nav_host_fragment
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootView.addView(navHostFragment)

        bottomNavigation = BottomNavigationView(this).apply {
            id = R.id.bottom_navigation
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            inflateMenu(R.menu.bottom_nav_menu)
            labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
            isItemHorizontalTranslationEnabled = false
            itemIconTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.nav_item_colors)
            itemTextColor = ContextCompat.getColorStateList(this@MainActivity, R.color.nav_item_colors)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card_background))
        }
        rootView.addView(bottomNavigation)

        setContentView(rootView)
        applyWindowInsets()

        WanderlyNotificationManager.createNotificationChannel(this)

        if (savedInstanceState == null) {
            val navHost = NavHostFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment, navHost)
                .setPrimaryNavigationFragment(navHost)
                .commit()
        }

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
        val navHostFrag = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFrag.navController
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
            bottomNavigation.visibility =
                if (
                    MainNavigationDestinations.shouldShowBottomNavigation(
                        currentDestinationId = destination.id,
                        onboardingDestinationId = R.id.onboardingFragment
                    )
                ) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            routePendingInviteIfNeeded(destination.id)
        }

        lifecycleScope.launch {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            bottomNavigation.setupWithNavController(navController)
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
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            navHostFragment.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = 0
            )
            bottomNavigation.updatePadding(
                left = systemBars.left,
                right = systemBars.right,
                bottom = systemBars.bottom
            )
            insets
        }
    }

    private fun setupObservers() {
        viewModel.streakMessage.observe(this) { message ->
            message?.let {
                Snackbar.make(rootView, it, Snackbar.LENGTH_LONG).show()
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
        AlertDialog.Builder(this, R.style.Wanderly_AlertDialog)
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
        if (NotificationPermissionManager.hasNotificationPermission(this)) {
            return
        }

        val requestedBefore = NotificationPermissionManager.hasRequestedPermissionBefore(this)
        val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        )

        when (
            NotificationPermissionManager.resolveRequestAction(
                status = NotificationPermissionManager.status(this),
                requestedBefore = requestedBefore,
                shouldShowRationale = shouldShowRationale
            )
        ) {
            NotificationPermissionManager.RequestAction.NONE -> Unit
            NotificationPermissionManager.RequestAction.SHOW_RATIONALE -> showNotificationPermissionRationale()
            NotificationPermissionManager.RequestAction.OPEN_SETTINGS -> showNotificationPermissionSettingsDialog()
            NotificationPermissionManager.RequestAction.REQUEST_PERMISSION -> launchNotificationPermissionRequest()
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
        NotificationPermissionManager.markPermissionRequested(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openNotificationSettings() {
        startActivity(NotificationPermissionManager.notificationSettingsIntent(this))
    }

    private fun routePendingInviteIfNeeded(destinationId: Int?) {
        if (!shouldRoutePendingInvite || !bottomNavigationReady) return
        if (destinationId == null || destinationId == R.id.onboardingFragment) return

        shouldRoutePendingInvite = false
        if (destinationId == R.id.socialFragment) return

        bottomNavigation.post {
            bottomNavigation.selectedItemId = R.id.socialFragment
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            stopHiveService()
        }
        super.onDestroy()
    }
}
