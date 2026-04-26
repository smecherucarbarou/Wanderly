package com.novahorizon.wanderly.ui.missions

import android.content.Context
import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.MissionDetailsRepository
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.WanderlyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class MissionsViewModelTest {

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
    fun `fetchPlaceDetails emits details on success`() = runTest {
        val detailsRepository = FakeMissionDetailsRepository(response = "A verified local summary.")
        val (viewModel, store) = createViewModel(detailsRepository)
        val states = viewModel.observeMissionStates()

        try {
            viewModel.fetchPlaceDetails()
            advanceUntilIdle()

            assertTrue(states.any { it is MissionsViewModel.MissionState.FetchingDetails })
            assertEquals(
                MissionsViewModel.MissionState.DetailsReceived("A verified local summary."),
                states.last()
            )
            assertEquals("Test Cafe" to "Bucharest", detailsRepository.lastRequest)
        } finally {
            store.clear()
            viewModel.missionState.removeObserver(states.observer)
        }
    }

    @Test
    fun `fetchPlaceDetails emits localized error on network failure`() = runTest {
        val detailsRepository = FakeMissionDetailsRepository(
            error = IOException("raw socket failure from upstream")
        )
        val (viewModel, store) = createViewModel(detailsRepository)
        val states = viewModel.observeMissionStates()

        try {
            viewModel.fetchPlaceDetails()
            advanceUntilIdle()

            val error = states.last() as MissionsViewModel.MissionState.Error
            assertEquals(context.getString(R.string.error_generic_retry), error.message)
            assertFalse(error.message.contains("raw socket failure"))
        } finally {
            store.clear()
            viewModel.missionState.removeObserver(states.observer)
        }
    }

    @Test
    fun `fetchPlaceDetails does not update after ViewModel is cleared`() = runTest {
        val detailsRepository = BlockingMissionDetailsRepository()
        val (viewModel, store) = createViewModel(detailsRepository)
        val states = viewModel.observeMissionStates()

        viewModel.fetchPlaceDetails()
        advanceUntilIdle()
        store.clear()
        advanceUntilIdle()

        assertTrue(detailsRepository.started)
        assertTrue(detailsRepository.cancelled)
        assertTrue(states.any { it is MissionsViewModel.MissionState.FetchingDetails })
        assertFalse(states.any { it is MissionsViewModel.MissionState.DetailsReceived })
        assertFalse(states.any { it is MissionsViewModel.MissionState.Error })
        viewModel.missionState.removeObserver(states.observer)
    }

    private fun createViewModel(
        detailsRepository: MissionDetailsRepository,
        repository: TestWanderlyRepository = TestWanderlyRepository(context)
    ): Pair<MissionsViewModel, ViewModelStore> {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MissionsViewModel(
                    repository,
                    SavedStateHandle(),
                    ProfileStateProvider(repository),
                    detailsRepository
                ) as T
            }
        }
        return ViewModelProvider(store, factory)[MissionsViewModel::class.java] to store
    }

    private fun MissionsViewModel.observeMissionStates(): ObservedStates {
        val states = ObservedStates()
        missionState.observeForever(states.observer)
        return states
    }

    private class ObservedStates : ArrayList<MissionsViewModel.MissionState>() {
        val observer = Observer<MissionsViewModel.MissionState> { add(it) }
    }

    private class TestWanderlyRepository(context: Context) : WanderlyRepository(context) {
        private val profileFlow = MutableStateFlow<Profile?>(null)

        override val currentProfile: StateFlow<Profile?> = profileFlow

        override suspend fun getCurrentProfile(): Profile? = profileFlow.value

        override suspend fun getMissionTarget(): String = "Test Cafe"

        override suspend fun getMissionCity(): String = "Bucharest"
    }

    private class FakeMissionDetailsRepository(
        private val response: String? = null,
        private val error: Exception? = null
    ) : MissionDetailsRepository() {
        var lastRequest: Pair<String, String>? = null
            private set

        override suspend fun getPlaceDetails(placeName: String, targetCity: String): String {
            lastRequest = placeName to targetCity
            error?.let { throw it }
            return requireNotNull(response)
        }
    }

    private class BlockingMissionDetailsRepository : MissionDetailsRepository() {
        var started = false
            private set
        var cancelled = false
            private set

        override suspend fun getPlaceDetails(placeName: String, targetCity: String): String {
            started = true
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancelled = true }
            }
        }
    }
}
