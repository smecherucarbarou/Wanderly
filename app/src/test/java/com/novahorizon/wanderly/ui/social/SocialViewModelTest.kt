package com.novahorizon.wanderly.ui.social

import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SocialViewModelTest {

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
    fun `loadLeaderboard emits loaded state`() = runTest {
        val leaderboard = listOf(testProfile("leader", "Queen Bee", honey = 400))
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(context, leaderboard = leaderboard)
        )

        try {
            viewModel.loadLeaderboard()
            advanceUntilIdle()

            assertEquals(leaderboard, viewModel.leaderboard.value)
            assertEquals(
                SocialViewModel.SocialUiState.Loaded(
                    friends = emptyList(),
                    leaderboard = leaderboard
                ),
                viewModel.state.value
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun `loadLeaderboard emits empty state`() = runTest {
        val (viewModel, store) = createViewModel(TestWanderlyRepository(context))

        try {
            viewModel.loadLeaderboard()
            advanceUntilIdle()

            assertEquals(emptyList<Profile>(), viewModel.leaderboard.value)
            assertEquals(SocialViewModel.SocialUiState.Empty, viewModel.state.value)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `loadLeaderboard emits error state`() = runTest {
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(
                context = context,
                leaderboardError = IOException("raw leaderboard failure")
            )
        )

        try {
            viewModel.loadLeaderboard()
            advanceUntilIdle()

            assertEquals(
                SocialViewModel.SocialUiState.Error(R.string.error_network),
                viewModel.state.value
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun `addFriend success refreshes friends`() = runTest {
        val friends = listOf(testProfile("friend", "Friend Bee", honey = 120))
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(
                context = context,
                friends = friends,
                addFriendResult = "Friend added successfully!"
            )
        )

        try {
            viewModel.addFriend("ABC123")
            advanceUntilIdle()

            assertEquals("Friend added successfully!", viewModel.addFriendResult.value)
            assertEquals(friends, viewModel.friends.value)
            assertEquals(
                SocialViewModel.SocialUiState.Loaded(
                    friends = friends,
                    leaderboard = emptyList()
                ),
                viewModel.state.value
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun `addFriend already friends shows specific error`() = runTest {
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(
                context = context,
                addFriendResult = "Already friends with this user"
            )
        )

        try {
            viewModel.addFriend("ABC123")
            advanceUntilIdle()

            assertEquals(
                context.getString(R.string.social_friend_already_added),
                viewModel.addFriendResult.value
            )
            assertEquals(
                SocialViewModel.SocialUiState.Error(R.string.social_friend_already_added),
                viewModel.state.value
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun `addFriend network error shows user-friendly message`() = runTest {
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(
                context = context,
                addFriendError = IOException("raw add friend failure")
            )
        )

        try {
            viewModel.addFriend("ABC123")
            advanceUntilIdle()

            val message = viewModel.addFriendResult.value.orEmpty()
            assertEquals(context.getString(R.string.social_add_friend_failed), message)
            assertFalse(message.contains("raw add friend"))
            assertEquals(
                SocialViewModel.SocialUiState.Error(R.string.error_network),
                viewModel.state.value
            )
        } finally {
            store.clear()
        }
    }

    private fun createViewModel(
        repository: TestWanderlyRepository
    ): Pair<SocialViewModel, ViewModelStore> {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SocialViewModel(repository) as T
            }
        }
        return ViewModelProvider(store, factory)[SocialViewModel::class.java] to store
    }

    private class TestWanderlyRepository(
        context: Context,
        private val leaderboard: List<Profile> = emptyList(),
        private val friends: List<Profile> = emptyList(),
        private val leaderboardError: Exception? = null,
        private val addFriendResult: String = "Friend added successfully!",
        private val addFriendError: Exception? = null
    ) : WanderlyRepository(context) {
        private val profileFlow = MutableStateFlow<Profile?>(null)

        override val currentProfile: StateFlow<Profile?> = profileFlow

        override suspend fun getCurrentProfile(): Profile? = profileFlow.value

        override suspend fun getLeaderboard(): List<Profile> {
            leaderboardError?.let { throw it }
            return leaderboard
        }

        override suspend fun getFriends(): List<Profile> = friends

        override suspend fun addFriendByCode(friendCode: String): String {
            addFriendError?.let { throw it }
            return addFriendResult
        }
    }

    private fun testProfile(id: String, username: String, honey: Int): Profile = Profile(
        id = id,
        username = username,
        honey = honey,
        hive_rank = 1,
        friend_code = "ABC123"
    )
}
