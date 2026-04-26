package com.novahorizon.wanderly.ui.gems

import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.DiscoveredPlace
import com.novahorizon.wanderly.data.Gem
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
class GemsViewModelTest {

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
    fun `loadGems emits loading before loaded results`() = runTest {
        val repository = TestWanderlyRepository(
            context = context,
            candidates = listOf(testPlace()),
            gems = listOf(testGem())
        )
        val (viewModel, store) = createViewModel(repository)
        val states = viewModel.observeGemStates()

        try {
            viewModel.loadGems(44.0, 26.0, "Bucharest")
            advanceUntilIdle()

            val loading = states[1] as GemsViewModel.GemsState.Loading
            assertEquals(context.getString(R.string.gems_loading_city_format, "Bucharest"), loading.message)
            assertEquals(GemsViewModel.GemsState.Loaded(listOf(testGem())), states.last())
        } finally {
            store.clear()
            viewModel.gemsState.removeObserver(states.observer)
        }
    }

    @Test
    fun `loadGems emits user-friendly error on failure`() = runTest {
        val repository = TestWanderlyRepository(
            context = context,
            fetchError = IOException("raw upstream gem failure")
        )
        val (viewModel, store) = createViewModel(repository)
        val states = viewModel.observeGemStates()
        val messages = viewModel.observeMessages()

        try {
            viewModel.loadGems(44.0, 26.0, "Bucharest")
            advanceUntilIdle()

            val error = states.last() as GemsViewModel.GemsState.Error
            assertEquals(context.getString(R.string.gems_loading_failed), error.message)
            assertFalse(error.message.contains("raw upstream"))
            assertEquals(context.getString(R.string.gems_loading_failed), messages.last())
        } finally {
            store.clear()
            viewModel.gemsState.removeObserver(states.observer)
            viewModel.message.removeObserver(messages.observer)
        }
    }

    @Test
    fun `loadGems emits empty state when no fresh candidates are available`() = runTest {
        val repository = TestWanderlyRepository(context = context, candidates = emptyList())
        val (viewModel, store) = createViewModel(repository)
        val states = viewModel.observeGemStates()
        val messages = viewModel.observeMessages()

        try {
            viewModel.loadGems(44.0, 26.0, "Bucharest")
            advanceUntilIdle()

            assertEquals(
                GemsViewModel.GemsState.Empty(context.getString(R.string.gems_empty_state)),
                states.last()
            )
            assertEquals(context.getString(R.string.gems_no_fresh_results), messages.last())
            assertEquals(0, repository.curateCalls)
        } finally {
            store.clear()
            viewModel.gemsState.removeObserver(states.observer)
            viewModel.message.removeObserver(messages.observer)
        }
    }

    private fun createViewModel(
        repository: TestWanderlyRepository
    ): Pair<GemsViewModel, ViewModelStore> {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return GemsViewModel(repository) as T
            }
        }
        return ViewModelProvider(store, factory)[GemsViewModel::class.java] to store
    }

    private fun GemsViewModel.observeGemStates(): ObservedGemStates {
        val states = ObservedGemStates()
        gemsState.observeForever(states.observer)
        return states
    }

    private fun GemsViewModel.observeMessages(): ObservedMessages {
        val messages = ObservedMessages()
        message.observeForever(messages.observer)
        return messages
    }

    private class ObservedGemStates : ArrayList<GemsViewModel.GemsState>() {
        val observer = Observer<GemsViewModel.GemsState> { add(it) }
    }

    private class ObservedMessages : ArrayList<String?>() {
        val observer = Observer<String?> { add(it) }
    }

    private class TestWanderlyRepository(
        context: Context,
        private val candidates: List<DiscoveredPlace> = emptyList(),
        private val gems: List<Gem> = emptyList(),
        private val fetchError: Exception? = null
    ) : WanderlyRepository(context) {
        private val profileFlow = MutableStateFlow<Profile?>(null)
        var curateCalls = 0
            private set

        override val currentProfile: StateFlow<Profile?> = profileFlow

        override suspend fun getCurrentProfile(): Profile? = profileFlow.value

        override suspend fun fetchHiddenGemCandidates(
            lat: Double,
            lng: Double,
            radius: Int,
            city: String?
        ): List<DiscoveredPlace> {
            fetchError?.let { throw it }
            return candidates
        }

        override suspend fun curateHiddenGems(
            city: String,
            candidates: List<DiscoveredPlace>,
            seenGemsHistory: Set<String>
        ): List<Gem> {
            curateCalls++
            return gems
        }
    }

    private fun testPlace(): DiscoveredPlace = DiscoveredPlace(
        name = "Test Cafe",
        lat = 44.4268,
        lng = 26.1025,
        category = "Food",
        areaLabel = "Bucharest",
        source = "google"
    )

    private fun testGem(): Gem = Gem(
        name = "Test Cafe",
        description = "A stylish coffee stop.",
        location = "Bucharest",
        reason = "Worth a quick detour.",
        category = "Food",
        lat = 44.4268,
        lng = 26.1025
    )
}
