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
import com.novahorizon.wanderly.data.AddFriendResult
import com.novahorizon.wanderly.data.FriendRequestActionResult
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.compose.screens.social.SocialScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.ui.social.SocialViewModel
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
class SocialScreenTest {

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
    fun `shows leaderboard and friends tabs`() {
        val (viewModel, store) = createViewModel()

        try {
            composeTestRule.setContent {
                WanderlyTheme {
                    SocialScreen(
                        viewModel = viewModel,
                        onAddFriend = {}
                    )
                }
            }

            composeTestRule.onNodeWithText("Leaderboard").assertIsDisplayed()
            composeTestRule.onNodeWithText("Friends").assertIsDisplayed()
        } finally {
            store.clear()
        }
    }

    @Test
    fun `shows add friend input`() {
        val (viewModel, store) = createViewModel()

        try {
            composeTestRule.setContent {
                WanderlyTheme {
                    SocialScreen(
                        viewModel = viewModel,
                        onAddFriend = {}
                    )
                }
            }

            composeTestRule.onNodeWithText("Enter their 6-character friend code").assertIsDisplayed()
        } finally {
            store.clear()
        }
    }

    private fun createViewModel(): Pair<SocialViewModel, ViewModelStore> {
        val repository = object : WanderlyRepository(context) {
            private val profileFlow = MutableStateFlow<Profile?>(null)
            override val currentProfile: StateFlow<Profile?> = profileFlow
            override suspend fun getCurrentProfile(): Profile? = null
            override suspend fun getLeaderboard(): List<Profile> = emptyList()
            override suspend fun getFriends(): List<Profile> = emptyList()
            override suspend fun getIncomingFriendRequests(): List<Profile> = emptyList()
            override suspend fun addFriendByCodeResult(code: String) = AddFriendResult.Failure
            override suspend fun acceptFriendRequest(requesterId: String) = FriendRequestActionResult.Accepted
            override suspend fun rejectFriendRequest(requesterId: String) = FriendRequestActionResult.Rejected
        }

        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SocialViewModel(repository) as T
            }
        }
        return ViewModelProvider(store, factory)[SocialViewModel::class.java] to store
    }
}
