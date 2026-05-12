package com.novahorizon.wanderly.ui.compose

import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.novahorizon.wanderly.data.AuthRepository
import com.novahorizon.wanderly.data.LogoutCoordinator
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.compose.screens.profile.ProfileScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.ui.profile.ProfileViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `shows loading state initially`() {
        val (viewModel, store) = createViewModel()

        try {
            composeTestRule.setContent {
                WanderlyTheme {
                    ProfileScreen(
                        viewModel = viewModel,
                        onLogout = {},
                        onSettings = {},
                        onEditAvatar = {}
                    )
                }
            }

            composeTestRule.onNodeWithText("Preparing your hive").assertIsDisplayed()
        } finally {
            store.clear()
        }
    }

    private fun createViewModel(): Pair<ProfileViewModel, ViewModelStore> {
        val repository = object : WanderlyRepository(context) {
            private val profileFlow = MutableStateFlow<Profile?>(null)
            override val currentProfile: StateFlow<Profile?> = profileFlow
            override suspend fun getCurrentProfile(): Profile? = null
        }
        val authRepository = object : AuthRepository() {
            override suspend fun signInWithEmail(email: String, password: String) {}
        }
        val logoutCoordinator = LogoutCoordinator(
            signOut = {},
            stopRealtime = {},
            cancelUserWork = {},
            clearNotificationState = {},
            clearLocalState = {},
            cancelWidgetRefresh = {}
        )

        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProfileViewModel(
                    repository,
                    ProfileStateProvider(repository),
                    authRepository,
                    logoutCoordinator
                ) as T
            }
        }
        return ViewModelProvider(store, factory)[ProfileViewModel::class.java] to store
    }
}
