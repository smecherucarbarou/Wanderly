package com.novahorizon.wanderly

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.novahorizon.wanderly.auth.AuthRouting
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.notifications.NotificationPermissionManager
import com.novahorizon.wanderly.notifications.WanderlyNotificationManager
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.services.HiveRealtimeService
import com.novahorizon.wanderly.ui.MainNavigationDestinations
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.ui.guide.WanderlyGuideFragment
import com.novahorizon.wanderly.ui.gems.GemsDestination
import com.novahorizon.wanderly.ui.main.DevDashboardRoute
import com.novahorizon.wanderly.ui.main.GemsRoute
import com.novahorizon.wanderly.ui.main.GuideRoute
import com.novahorizon.wanderly.ui.main.MainNavCommand
import com.novahorizon.wanderly.ui.main.MainNavViewModel
import com.novahorizon.wanderly.ui.main.MainViewModel
import com.novahorizon.wanderly.ui.main.MapRoute
import com.novahorizon.wanderly.ui.main.MissionsRoute
import com.novahorizon.wanderly.ui.main.OnboardingRoute
import com.novahorizon.wanderly.ui.main.ProfileRoute
import com.novahorizon.wanderly.ui.main.SocialRoute
import com.novahorizon.wanderly.ui.map.MapFragment
import com.novahorizon.wanderly.ui.missions.MissionsFragment
import com.novahorizon.wanderly.ui.onboarding.OnboardingFragment
import com.novahorizon.wanderly.ui.profile.DevDashboardFragment
import com.novahorizon.wanderly.ui.profile.ProfileFragment
import com.novahorizon.wanderly.ui.social.SocialFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var repository: WanderlyRepository

    private val viewModel: MainViewModel by viewModels()

    /** Activity-scoped bridge: hosted Fragments emit nav commands the Compose NavHost executes. */
    private val mainNavViewModel: MainNavViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /** null until onboarding/pending-invite state is resolved, then drives the NavHost start destination. */
    private val startState = mutableStateOf<MainStartState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        WanderlyNotificationManager.createNotificationChannel(this)

        setContent {
            WanderlyTheme {
                when (val start = startState.value) {
                    null -> MainLoading()
                    else -> MainHost(
                        startState = start,
                        mainViewModel = viewModel,
                        navCommands = mainNavViewModel.commands,
                        onStreakCrisis = ::showStreakCrisisDialog,
                        onSessionResolved = ::startHiveService
                    )
                }
            }
        }

        lifecycleScope.launch {
            val onboardingSeen = withContext(Dispatchers.IO) {
                repository.isOnboardingSeenSuspend()
            }
            val hasPendingInvite = withContext(Dispatchers.IO) {
                !repository.peekPendingInviteCode().isNullOrBlank()
            }
            startState.value = MainStartState(onboardingSeen, hasPendingInvite)
        }

        viewModel.checkDailyStreak()
    }

    private fun startHiveService(hasSession: Boolean) {
        // HiveRealtimeService is disabled (HiveRealtimeService.ENABLE_PROFILE_REALTIME = false): starting it
        // would only post a permanent idle foreground notification while doing no work. Skip the start. To
        // re-enable profile realtime: flip the flag and restore the <service> entry in AndroidManifest.xml —
        // this gate then allows the start again.
        if (!HiveRealtimeService.ENABLE_PROFILE_REALTIME) return
        if (!AuthRouting.shouldStartSessionServices(hasSession)) return
        val intent = Intent(this, HiveRealtimeService::class.java)
        try {
            startForegroundService(intent)
        } catch (e: IllegalStateException) {
            // Android 12+ can reject a foreground-service start when the app isn't in an allowed state
            // (ForegroundServiceStartNotAllowedException, a subclass of IllegalStateException). Best-effort:
            // log and continue rather than crash.
            AppLogger.w("MainActivity", "Could not start HiveRealtimeService: ${e.message}")
        }
    }

    private fun stopHiveService() {
        stopService(Intent(this, HiveRealtimeService::class.java))
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

    override fun onDestroy() {
        if (isFinishing) {
            stopHiveService()
        }
        super.onDestroy()
    }
}

private data class ColoredSnackbarVisuals(
    override val message: String,
    val isError: Boolean,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Long
) : SnackbarVisuals

suspend fun SnackbarHostState.showColoredSnackbar(message: String, isError: Boolean) {
    showSnackbar(ColoredSnackbarVisuals(message = message, isError = isError))
}

private data class MainStartState(
    val onboardingSeen: Boolean,
    val hasPendingInvite: Boolean
)

private data class MainTab(
    val route: Any,
    val titleRes: Int,
    val iconRes: Int
)

private val MAIN_TABS = listOf(
    MainTab(MapRoute, R.string.title_map, android.R.drawable.ic_dialog_map),
    MainTab(GemsRoute, R.string.title_gems, android.R.drawable.ic_menu_search),
    MainTab(MissionsRoute, R.string.title_missions, android.R.drawable.ic_menu_agenda),
    MainTab(SocialRoute, R.string.title_social, android.R.drawable.ic_menu_share),
    MainTab(ProfileRoute, R.string.title_profile, android.R.drawable.ic_menu_myplaces)
)

@Composable
private fun MainLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun MainHost(
    startState: MainStartState,
    mainViewModel: MainViewModel,
    navCommands: Flow<MainNavCommand>,
    onStreakCrisis: (Int, Int) -> Unit,
    onSessionResolved: (Boolean) -> Unit
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val isOnboardingRoute = currentDestination?.hasRoute(OnboardingRoute::class)
        ?: MainNavigationDestinations.startsOnOnboarding(startState.onboardingSeen)
    val showBottomBar = MainNavigationDestinations.shouldShowBottomNavigation(isOnboardingRoute)

    // Resolve the auth session once, then start session-scoped services (hive realtime is gated/disabled).
    LaunchedEffect(Unit) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
        onSessionResolved(session != null)
    }

    // Bridge: hosted Fragments emit nav commands because the Compose NavController now owns the back stack.
    LaunchedEffect(navController) {
        navCommands.collect { command ->
            when (command) {
                MainNavCommand.ToMissions -> navController.navigateToTab(MissionsRoute)
                MainNavCommand.ToGuide -> navController.navigate(GuideRoute)
                MainNavCommand.ToDevDashboard -> navController.navigate(DevDashboardRoute) {
                    launchSingleTop = true
                }
                MainNavCommand.AfterOnboarding -> navController.navigate(MapRoute) {
                    popUpTo(OnboardingRoute) { inclusive = true }
                    launchSingleTop = true
                }
                MainNavCommand.Back -> navController.popBackStack()
            }
        }
    }

    // Pending deeplink invite: once off onboarding, jump to the Social tab exactly once.
    var inviteRouted by rememberSaveable { mutableStateOf(!startState.hasPendingInvite) }
    LaunchedEffect(currentDestination) {
        if (inviteRouted) return@LaunchedEffect
        val destination = currentDestination ?: return@LaunchedEffect
        if (destination.hasRoute(OnboardingRoute::class)) return@LaunchedEffect
        inviteRouted = true
        if (!destination.hasRoute(SocialRoute::class)) {
            navController.navigateToTab(SocialRoute)
        }
    }

    // Streak crisis dialog + streak snackbar (formerly MainViewModel observers on the Activity).
    val streakStatus by mainViewModel.streakStatus.observeAsState()
    LaunchedEffect(streakStatus) {
        (streakStatus as? MainViewModel.StreakStatus.Crisis)?.let { onStreakCrisis(it.lostStreak, it.cost) }
    }
    val streakMessage by mainViewModel.streakMessage.observeAsState()
    LaunchedEffect(streakMessage) {
        val message = streakMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            mainViewModel.clearStreakMessage()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val isError = (data.visuals as? ColoredSnackbarVisuals)?.isError == true
                Snackbar(
                    snackbarData = data,
                    containerColor = if (isError) colorResource(R.color.error) else colorResource(R.color.primary),
                    contentColor = if (isError) colorResource(R.color.card_background) else colorResource(R.color.secondary)
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                MainBottomBar(navController = navController, currentDestination = currentDestination)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (startState.onboardingSeen) MapRoute else OnboardingRoute,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable<OnboardingRoute> { AndroidFragment<OnboardingFragment>(modifier = Modifier.fillMaxSize()) }
            composable<MapRoute> { AndroidFragment<MapFragment>(modifier = Modifier.fillMaxSize()) }
            composable<GemsRoute> { GemsDestination(snackbarHostState = snackbarHostState) }
            composable<MissionsRoute> { AndroidFragment<MissionsFragment>(modifier = Modifier.fillMaxSize()) }
            composable<SocialRoute> { AndroidFragment<SocialFragment>(modifier = Modifier.fillMaxSize()) }
            composable<ProfileRoute> { AndroidFragment<ProfileFragment>(modifier = Modifier.fillMaxSize()) }
            composable<GuideRoute> { AndroidFragment<WanderlyGuideFragment>(modifier = Modifier.fillMaxSize()) }
            composable<DevDashboardRoute> { AndroidFragment<DevDashboardFragment>(modifier = Modifier.fillMaxSize()) }
        }
    }
}

@Composable
private fun MainBottomBar(
    navController: NavController,
    currentDestination: NavDestination?
) {
    NavigationBar(
        containerColor = colorResource(R.color.card_background)
    ) {
        MAIN_TABS.forEach { tab ->
            val selected = currentDestination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
            NavigationBarItem(
                selected = selected,
                onClick = { if (!selected) navController.navigateToTab(tab.route) },
                icon = {
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = stringResource(tab.titleRes)
                    )
                },
                label = { Text(stringResource(tab.titleRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colorResource(R.color.primary),
                    selectedTextColor = colorResource(R.color.primary),
                    unselectedIconColor = colorResource(R.color.text_secondary),
                    unselectedTextColor = colorResource(R.color.text_secondary),
                    indicatorColor = colorResource(R.color.primary).copy(alpha = 0.1f)
                )
            )
        }
    }
}

/** Bottom-nav semantics: pop to the Map (home) root saving state, single-top, restore the tab's saved state. */
private fun NavController.navigateToTab(route: Any) {
    navigate(route) {
        popUpTo(MapRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
