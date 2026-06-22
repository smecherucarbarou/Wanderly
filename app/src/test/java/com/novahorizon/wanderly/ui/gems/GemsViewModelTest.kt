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
import com.novahorizon.wanderly.data.GemDiscoveryResult
import com.novahorizon.wanderly.data.HiddenGemCandidateResult
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
            assertEquals(
                UiText.StringResource(R.string.gems_loading_city_format, listOf("Bucharest")),
                loading.message
            )
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
            assertEquals(UiText.StringResource(R.string.gems_loading_failed), error.message)
            assertFalse(error.message.asString(context).contains("raw upstream"))
            assertEquals(UiText.StringResource(R.string.gems_loading_failed), messages.last())
        } finally {
            store.clear()
            viewModel.gemsState.removeObserver(states.observer)
            viewModel.message.removeObserver(messages.observer)
        }
    }

    @Test
    fun `loadGems maps typed places error to visible error state`() = runTest {
        val repository = TestWanderlyRepository(
            context = context,
            candidateResult = HiddenGemCandidateResult.Error(
                reason = HiddenGemCandidateResult.Reason.Server,
                statusCode = 500,
                message = "proxy failure"
            )
        )
        val (viewModel, store) = createViewModel(repository)
        val states = viewModel.observeGemStates()
        val messages = viewModel.observeMessages()

        try {
            viewModel.loadGems(44.0, 26.0, "Bucharest")
            advanceUntilIdle()

            assertEquals(
                GemsViewModel.GemsState.Error(UiText.StringResource(R.string.gems_loading_failed)),
                states.last()
            )
            assertEquals(UiText.StringResource(R.string.gems_loading_failed), messages.last())
            assertEquals(0, repository.curateCalls)
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
                GemsViewModel.GemsState.Empty(UiText.StringResource(R.string.gems_empty_state)),
                states.last()
            )
            assertEquals(UiText.StringResource(R.string.gems_no_fresh_results), messages.last())
            assertEquals(0, repository.curateCalls)
        } finally {
            store.clear()
            viewModel.gemsState.removeObserver(states.observer)
            viewModel.message.removeObserver(messages.observer)
        }
    }

    @Test
    fun `discoverGem without location emits TooFar and skips repository`() = runTest {
        val repository = TestWanderlyRepository(context = context)
        val (viewModel, store) = createViewModel(repository)
        val events = viewModel.observeDiscoverEvents()

        try {
            viewModel.discoverGem(testGem())
            advanceUntilIdle()

            assertEquals(GemsViewModel.DiscoverEvent.TooFar, events.last())
            assertEquals(0, repository.discoverCalls)
        } finally {
            store.clear()
            viewModel.discoverEvent.removeObserver(events.observer)
        }
    }

    @Test
    fun `discoverGem success marks gem discovered and flags first gem`() = runTest {
        val repository = TestWanderlyRepository(
            context = context,
            discoverResult = GemDiscoveryResult.Success(10),
            discoveryCount = 1
        )
        val (viewModel, store) = createViewModel(repository)
        val events = viewModel.observeDiscoverEvents()
        val discovered = viewModel.observeDiscoveredGems()

        try {
            viewModel.updateCurrentLocation(44.4268, 26.1025)
            viewModel.discoverGem(testGem())
            advanceUntilIdle()

            assertEquals(
                GemsViewModel.DiscoverEvent.Discovered(rewardHoney = 10, firstGem = true),
                events.last()
            )
            assertTrue(discovered.last().contains("Test Cafe"))
            assertEquals(1, repository.discoverCalls)
            assertTrue(repository.getCurrentProfileCalls >= 1)
        } finally {
            store.clear()
            viewModel.discoverEvent.removeObserver(events.observer)
            viewModel.discoveredGems.removeObserver(discovered.observer)
        }
    }

    @Test
    fun `discoverGem success with existing discoveries is not first gem`() = runTest {
        val repository = TestWanderlyRepository(
            context = context,
            discoverResult = GemDiscoveryResult.Success(10),
            discoveryCount = 3
        )
        val (viewModel, store) = createViewModel(repository)
        val events = viewModel.observeDiscoverEvents()

        try {
            viewModel.updateCurrentLocation(44.4268, 26.1025)
            viewModel.discoverGem(testGem())
            advanceUntilIdle()

            assertEquals(
                GemsViewModel.DiscoverEvent.Discovered(rewardHoney = 10, firstGem = false),
                events.last()
            )
        } finally {
            store.clear()
            viewModel.discoverEvent.removeObserver(events.observer)
        }
    }

    @Test
    fun `discoverGem out of range emits TooFar`() = runTest {
        val repository = TestWanderlyRepository(
            context = context,
            discoverResult = GemDiscoveryResult.TooFar
        )
        val (viewModel, store) = createViewModel(repository)
        val events = viewModel.observeDiscoverEvents()

        try {
            viewModel.updateCurrentLocation(40.0, 20.0)
            viewModel.discoverGem(testGem())
            advanceUntilIdle()

            assertEquals(GemsViewModel.DiscoverEvent.TooFar, events.last())
            assertEquals(1, repository.discoverCalls)
        } finally {
            store.clear()
            viewModel.discoverEvent.removeObserver(events.observer)
        }
    }

    @Test
    fun `discoverGem already discovered still marks gem discovered`() = runTest {
        val repository = TestWanderlyRepository(
            context = context,
            discoverResult = GemDiscoveryResult.AlreadyDiscovered
        )
        val (viewModel, store) = createViewModel(repository)
        val events = viewModel.observeDiscoverEvents()
        val discovered = viewModel.observeDiscoveredGems()

        try {
            viewModel.updateCurrentLocation(44.4268, 26.1025)
            viewModel.discoverGem(testGem())
            advanceUntilIdle()

            assertEquals(GemsViewModel.DiscoverEvent.AlreadyDiscovered, events.last())
            assertTrue(discovered.last().contains("Test Cafe"))
        } finally {
            store.clear()
            viewModel.discoverEvent.removeObserver(events.observer)
            viewModel.discoveredGems.removeObserver(discovered.observer)
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

    private fun GemsViewModel.observeDiscoverEvents(): ObservedDiscoverEvents {
        val events = ObservedDiscoverEvents()
        discoverEvent.observeForever(events.observer)
        return events
    }

    private fun GemsViewModel.observeDiscoveredGems(): ObservedDiscoveredGems {
        val discovered = ObservedDiscoveredGems()
        discoveredGems.observeForever(discovered.observer)
        return discovered
    }

    private class ObservedGemStates : ArrayList<GemsViewModel.GemsState>() {
        val observer = Observer<GemsViewModel.GemsState> { add(it) }
    }

    private class ObservedMessages : ArrayList<UiText?>() {
        val observer = Observer<UiText?> { add(it) }
    }

    private class ObservedDiscoverEvents : ArrayList<GemsViewModel.DiscoverEvent?>() {
        val observer = Observer<GemsViewModel.DiscoverEvent?> { add(it) }
    }

    private class ObservedDiscoveredGems : ArrayList<Set<String>>() {
        val observer = Observer<Set<String>> { add(it) }
    }

    private class TestWanderlyRepository(
        context: Context,
        private val candidates: List<DiscoveredPlace> = emptyList(),
        private val gems: List<Gem> = emptyList(),
        private val candidateResult: HiddenGemCandidateResult? = null,
        private val fetchError: Exception? = null,
        private val discoverResult: GemDiscoveryResult = GemDiscoveryResult.Error,
        private val discoveryCount: Int = 0
    ) : WanderlyRepository(context) {
        private val profileFlow = MutableStateFlow<Profile?>(null)
        var curateCalls = 0
            private set
        var discoverCalls = 0
            private set
        var getCurrentProfileCalls = 0
            private set

        override val currentProfile: StateFlow<Profile?> = profileFlow

        override suspend fun getCurrentProfile(): Profile? {
            getCurrentProfileCalls++
            return profileFlow.value
        }

        override suspend fun discoverGem(
            gem: Gem,
            currentLat: Double,
            currentLng: Double
        ): GemDiscoveryResult {
            discoverCalls++
            return discoverResult
        }

        override suspend fun countGemDiscoveries(): Int = discoveryCount

        override suspend fun fetchHiddenGemCandidates(
            lat: Double,
            lng: Double,
            radius: Int,
            city: String?
        ): List<DiscoveredPlace> {
            fetchError?.let { throw it }
            return candidates
        }

        override suspend fun fetchHiddenGemCandidatesResult(
            lat: Double,
            lng: Double,
            radius: Int,
            city: String?
        ): HiddenGemCandidateResult {
            candidateResult?.let { return it }
            fetchError?.let { throw it }
            return HiddenGemCandidateResult.Success(candidates)
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
