package com.novahorizon.wanderly.ui.profile

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileStateProvider
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
import org.junit.Assert.assertTrue
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
class ProfileViewModelTest {

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
    fun `loadProfile transitions from loading to loaded`() = runTest {
        val profile = testProfile()
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(context, initialProfile = profile)
        )
        val states = viewModel.observeProfileStates()

        try {
            viewModel.loadProfile()
            advanceUntilIdle()

            assertTrue(states.first() is ProfileViewModel.ProfileUiState.Loading)
            assertEquals(ProfileViewModel.ProfileUiState.Loaded(profile), states.last())
            assertEquals(profile, viewModel.profile.value)
        } finally {
            store.clear()
            viewModel.profileState.removeObserver(states.observer)
        }
    }

    @Test
    fun `loadProfile emits error when repository fails`() = runTest {
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(
                context = context,
                refreshError = IOException("raw profile failure")
            )
        )
        val states = viewModel.observeProfileStates()

        try {
            viewModel.loadProfile()
            advanceUntilIdle()

            assertEquals(
                ProfileViewModel.ProfileUiState.Error(R.string.profile_load_failed),
                states.last()
            )
        } finally {
            store.clear()
            viewModel.profileState.removeObserver(states.observer)
        }
    }

    @Test
    fun `uploadAvatar emits updated event on success`() = runTest {
        val profile = testProfile()
        val repository = TestWanderlyRepository(
            context = context,
            initialProfile = profile,
            uploadResult = REMOTE_AVATAR_PATH
        )
        val (viewModel, store) = createViewModel(repository)
        val events = viewModel.observeProfileEvents()

        try {
            viewModel.uploadAvatar(profile, Uri.parse("content://wanderly/avatar.jpg"))
            advanceUntilIdle()

            assertEquals(
                ProfileViewModel.ProfileEvent.AvatarUpdated(REMOTE_AVATAR_PATH),
                events.last()
            )
            assertEquals(REMOTE_AVATAR_PATH, repository.updatedProfile?.avatar_url)
        } finally {
            store.clear()
            viewModel.profileEvent.removeObserver(events.observer)
        }
    }

    @Test
    fun `uploadAvatar emits user-friendly failure event`() = runTest {
        val profile = testProfile()
        val (viewModel, store) = createViewModel(
            TestWanderlyRepository(
                context = context,
                initialProfile = profile,
                uploadError = IOException("raw avatar failure")
            )
        )
        val events = viewModel.observeProfileEvents()

        try {
            viewModel.uploadAvatar(profile, Uri.parse("content://wanderly/avatar.jpg"))
            advanceUntilIdle()

            val event = events.last() as ProfileViewModel.ProfileEvent.ShowMessage
            assertEquals(R.string.profile_avatar_upload_failed, event.messageRes)
            assertTrue(event.isError)
        } finally {
            store.clear()
            viewModel.profileEvent.removeObserver(events.observer)
        }
    }

    private fun createViewModel(
        repository: TestWanderlyRepository
    ): Pair<ProfileViewModel, ViewModelStore> {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProfileViewModel(repository, ProfileStateProvider(repository)) as T
            }
        }
        return ViewModelProvider(store, factory)[ProfileViewModel::class.java] to store
    }

    private fun ProfileViewModel.observeProfileStates(): ObservedProfileStates {
        val states = ObservedProfileStates()
        profileState.observeForever(states.observer)
        return states
    }

    private fun ProfileViewModel.observeProfileEvents(): ObservedProfileEvents {
        val events = ObservedProfileEvents()
        profileEvent.observeForever(events.observer)
        return events
    }

    private class ObservedProfileStates : ArrayList<ProfileViewModel.ProfileUiState>() {
        val observer = Observer<ProfileViewModel.ProfileUiState> { add(it) }
    }

    private class ObservedProfileEvents : ArrayList<ProfileViewModel.ProfileEvent?>() {
        val observer = Observer<ProfileViewModel.ProfileEvent?> { add(it) }
    }

    private class TestWanderlyRepository(
        context: Context,
        initialProfile: Profile? = null,
        private val refreshError: Exception? = null,
        private val uploadResult: String = REMOTE_AVATAR_PATH,
        private val uploadError: Exception? = null,
        private val updateSucceeds: Boolean = true
    ) : WanderlyRepository(context) {
        private val profileFlow = MutableStateFlow(initialProfile)
        var updatedProfile: Profile? = null
            private set

        override val currentProfile: StateFlow<Profile?> = profileFlow

        override suspend fun getCurrentProfile(): Profile? {
            refreshError?.let { throw it }
            return profileFlow.value
        }

        override suspend fun updateProfile(profile: Profile): Boolean {
            updatedProfile = profile
            if (updateSucceeds) {
                profileFlow.value = profile
            }
            return updateSucceeds
        }

        override suspend fun uploadAvatar(uri: Uri, profileId: String): String {
            uploadError?.let { throw it }
            return uploadResult
        }
    }

    private fun testProfile(): Profile = Profile(
        id = "user-1",
        username = "Test Bee",
        honey = 50,
        hive_rank = 1,
        badges = listOf("Early Bee"),
        friend_code = "ABC123"
    )

    private companion object {
        const val REMOTE_AVATAR_PATH = "profiles/user-1/avatar.jpg"
    }
}
