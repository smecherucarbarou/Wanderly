package com.novahorizon.wanderly.ui.social

import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.AddFriendResult
import com.novahorizon.wanderly.data.FriendRequestActionResult
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.ui.common.UiText
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
    fun `loadFriends emits incoming requests with accepted friends`() = runTest {
        val friend = testProfile("friend", "Friend Bee", honey = 120)
        val requester = testProfile("requester", "Request Bee", honey = 90)
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(
                context = context,
                friends = listOf(friend),
                incomingFriendRequests = listOf(requester)
            )
        )

        try {
            viewModel.loadFriends()
            advanceUntilIdle()

            assertEquals(listOf(friend), viewModel.friends.value)
            assertEquals(listOf(requester), viewModel.incomingFriendRequests.value)
            assertEquals(
                SocialViewModel.SocialUiState.Loaded(
                    friends = listOf(friend),
                    incomingRequests = listOf(requester),
                    leaderboard = emptyList()
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
                SocialViewModel.SocialUiState.Error(UiText.StringResource(R.string.error_network)),
                viewModel.state.value
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun `addFriend request success keeps accepted friends state`() = runTest {
        val friends = listOf(testProfile("friend", "Friend Bee", honey = 120))
        val repository = TestWanderlyRepository(
            context = context,
            friends = friends,
            addFriendResult = AddFriendResult.FriendRequestSent
        )
        val (viewModel, store) = createViewModel(repository)

        try {
            viewModel.loadFriends()
            advanceUntilIdle()
            val loadCountBeforeAdd = repository.getFriendsCallCount

            viewModel.addFriend("ABC123")
            advanceUntilIdle()

            assertEquals(
                SocialViewModel.SocialMessage(
                    message = UiText.StringResource(R.string.social_friend_request_sent),
                    isError = false
                ),
                viewModel.addFriendResult.value
            )
            assertEquals(friends, viewModel.friends.value)
            assertEquals(loadCountBeforeAdd, repository.getFriendsCallCount)
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
    fun `addFriend pending request state is informational`() = runTest {
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(
                context = context,
                addFriendResult = AddFriendResult.AlreadyRequestedOrFriends
            )
        )

        try {
            viewModel.addFriend("ABC123")
            advanceUntilIdle()

            assertEquals(
                SocialViewModel.SocialMessage(
                    message = UiText.StringResource(R.string.social_friend_request_pending),
                    isError = false
                ),
                viewModel.addFriendResult.value
            )
            assertEquals(SocialViewModel.SocialUiState.Empty, viewModel.state.value)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `addFriend result failure emits error state`() = runTest {
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(
                context = context,
                addFriendResult = AddFriendResult.Failure
            )
        )

        try {
            viewModel.addFriend("ABC123")
            advanceUntilIdle()

            assertEquals(
                SocialViewModel.SocialMessage(
                    message = UiText.StringResource(R.string.social_add_friend_failed),
                    isError = true
                ),
                viewModel.addFriendResult.value
            )
            assertEquals(
                SocialViewModel.SocialUiState.Error(UiText.StringResource(R.string.social_add_friend_failed)),
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

            val result = requireNotNull(viewModel.addFriendResult.value)
            assertEquals(UiText.StringResource(R.string.social_add_friend_failed), result.message)
            assertEquals(true, result.isError)
            assertFalse(result.message.asString(context).contains("raw add friend"))
            assertEquals(
                SocialViewModel.SocialUiState.Error(UiText.StringResource(R.string.error_network)),
                viewModel.state.value
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun `acceptFriendRequest success refreshes social lifecycle state`() = runTest {
        val requester = testProfile("requester", "Request Bee", honey = 90)
        val accepted = testProfile("requester", "Request Bee", honey = 90)
        val repository = TestWanderlyRepository(
            context = context,
            incomingFriendRequests = listOf(requester),
            friendsAfterAction = listOf(accepted),
            incomingAfterAction = emptyList(),
            acceptResult = FriendRequestActionResult.Accepted
        )
        val (viewModel, store) = createViewModel(repository)

        try {
            viewModel.acceptFriendRequest("requester")
            advanceUntilIdle()

            assertEquals(listOf("requester"), repository.acceptedRequestIds)
            assertEquals(
                SocialViewModel.SocialMessage(
                    message = UiText.StringResource(R.string.social_friend_request_accepted),
                    isError = false
                ),
                viewModel.addFriendResult.value
            )
            assertEquals(listOf(accepted), viewModel.friends.value)
            assertEquals(emptyList<Profile>(), viewModel.incomingFriendRequests.value)
            assertEquals(
                SocialViewModel.SocialUiState.Loaded(
                    friends = listOf(accepted),
                    incomingRequests = emptyList(),
                    leaderboard = emptyList()
                ),
                viewModel.state.value
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun `rejectFriendRequest success removes incoming request`() = runTest {
        val requester = testProfile("requester", "Request Bee", honey = 90)
        val repository = TestWanderlyRepository(
            context = context,
            incomingFriendRequests = listOf(requester),
            incomingAfterAction = emptyList(),
            rejectResult = FriendRequestActionResult.Rejected
        )
        val (viewModel, store) = createViewModel(repository)

        try {
            viewModel.rejectFriendRequest("requester")
            advanceUntilIdle()

            assertEquals(listOf("requester"), repository.rejectedRequestIds)
            assertEquals(
                SocialViewModel.SocialMessage(
                    message = UiText.StringResource(R.string.social_friend_request_rejected),
                    isError = false
                ),
                viewModel.addFriendResult.value
            )
            assertEquals(emptyList<Profile>(), viewModel.incomingFriendRequests.value)
            assertEquals(SocialViewModel.SocialUiState.Empty, viewModel.state.value)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `acceptFriendRequest failure remains an error`() = runTest {
        val requester = testProfile("requester", "Request Bee", honey = 90)
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(
                context = context,
                incomingFriendRequests = listOf(requester),
                acceptResult = FriendRequestActionResult.Failure
            )
        )

        try {
            viewModel.acceptFriendRequest("requester")
            advanceUntilIdle()

            assertEquals(
                SocialViewModel.SocialMessage(
                    message = UiText.StringResource(R.string.social_friend_request_action_failed),
                    isError = true
                ),
                viewModel.addFriendResult.value
            )
            assertEquals(
                SocialViewModel.SocialUiState.Error(UiText.StringResource(R.string.social_friend_request_action_failed)),
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
        private val incomingFriendRequests: List<Profile> = emptyList(),
        private val friendsAfterAction: List<Profile> = friends,
        private val incomingAfterAction: List<Profile> = incomingFriendRequests,
        private val leaderboardError: Exception? = null,
        private val addFriendResult: AddFriendResult = AddFriendResult.FriendRequestSent,
        private val addFriendError: Exception? = null,
        private val acceptResult: FriendRequestActionResult = FriendRequestActionResult.Accepted,
        private val rejectResult: FriendRequestActionResult = FriendRequestActionResult.Rejected
    ) : WanderlyRepository(context) {
        private val profileFlow = MutableStateFlow<Profile?>(null)
        private var actionCompleted = false
        var getFriendsCallCount: Int = 0
            private set
        val acceptedRequestIds = mutableListOf<String>()
        val rejectedRequestIds = mutableListOf<String>()

        override val currentProfile: StateFlow<Profile?> = profileFlow

        override suspend fun getCurrentProfile(): Profile? = profileFlow.value

        override suspend fun getLeaderboard(): List<Profile> {
            leaderboardError?.let { throw it }
            return leaderboard
        }

        override suspend fun getFriends(): List<Profile> {
            getFriendsCallCount += 1
            return if (actionCompleted) friendsAfterAction else friends
        }

        override suspend fun getIncomingFriendRequests(): List<Profile> {
            return if (actionCompleted) incomingAfterAction else incomingFriendRequests
        }

        override suspend fun addFriendByCodeResult(friendCode: String): AddFriendResult {
            addFriendError?.let { throw it }
            return addFriendResult
        }

        override suspend fun acceptFriendRequest(requesterId: String): FriendRequestActionResult {
            acceptedRequestIds += requesterId
            actionCompleted = acceptResult == FriendRequestActionResult.Accepted
            return acceptResult
        }

        override suspend fun rejectFriendRequest(requesterId: String): FriendRequestActionResult {
            rejectedRequestIds += requesterId
            actionCompleted = rejectResult == FriendRequestActionResult.Rejected
            return rejectResult
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
