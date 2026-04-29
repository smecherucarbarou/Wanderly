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
import com.novahorizon.wanderly.WanderlyGraph
import com.novahorizon.wanderly.data.MissionDetailsRepository
import com.novahorizon.wanderly.data.MissionCompletionResult
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.data.mission.MissionCandidateProvider
import com.novahorizon.wanderly.data.mission.MissionPlaceCandidate
import com.novahorizon.wanderly.data.mission.MissionPlaceSelectionResult
import com.novahorizon.wanderly.data.mission.MissionPlaceSelecting
import com.novahorizon.wanderly.data.mission.ValidatedMissionPlace
import com.novahorizon.wanderly.ui.common.UiText
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
        WanderlyGraph.resetTestOverrides()
    }

    @After
    fun tearDown() {
        WanderlyGraph.resetTestOverrides()
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
            assertEquals(UiText.StringResource(R.string.error_generic_retry), error.message)
            assertFalse(error.message.asString(context).contains("raw socket failure"))
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

    @Test
    fun `generateMission stores verified destination mission when resolution succeeds`() = runTest {
        val candidateProvider = FakeMissionCandidateProvider(
            candidates = listOf(
                MissionPlaceCandidate(
                    name = "Test Cafe",
                    query = "Test Cafe Bucharest Romania"
                )
            )
        )
        val selector = FakeMissionPlaceSelector(
            result = MissionPlaceSelectionResult.Success(
                validatedPlace(
                    candidate = candidateProvider.candidates.single(),
                    name = "Verified Cafe",
                    lat = 44.427,
                    lng = 26.103
                )
            )
        )
        val repository = TestWanderlyRepository(
            context = context,
            nearbyPlaces = listOf("Test Cafe")
        )
        val (viewModel, store) = createViewModel(
            detailsRepository = FakeMissionDetailsRepository("details"),
            repository = repository,
            candidateProvider = candidateProvider,
            placeSelector = selector
        )
        val states = viewModel.observeMissionStates()

        try {
            viewModel.generateMission(44.4268, 26.1025, "Bucharest")
            advanceUntilIdle()

            val received = states.last() as MissionsViewModel.MissionState.MissionReceived
            assertTrue(received.text.contains("Verified Cafe"))
            assertEquals("Verified Cafe", repository.savedMission?.target)
            assertEquals(44.427, repository.savedMission?.targetLat)
            assertEquals(26.103, repository.savedMission?.targetLng)
            assertFalse(received.text.contains("Could not verify a specific destination"))
            assertEquals(1, candidateProvider.calls)
            assertEquals(1, selector.requests.single().candidates.size)
        } finally {
            store.clear()
            viewModel.missionState.removeObserver(states.observer)
        }
    }

    @Test
    fun `generateMission succeeds when second candidate validates`() = runTest {
        val first = MissionPlaceCandidate(name = "Too Far Monument", query = "Too Far Monument Bucharest Romania")
        val second = MissionPlaceCandidate(name = "The Infinity Column", query = "The Infinity Column Bucharest Romania")
        val candidateProvider = FakeMissionCandidateProvider(candidates = listOf(first, second))
        val selector = FakeMissionPlaceSelector(
            result = MissionPlaceSelectionResult.Success(
                validatedPlace(candidate = second, name = "The Infinity Column")
            )
        )
        val repository = TestWanderlyRepository(context = context, nearbyPlaces = emptyList())
        val (viewModel, store) = createViewModel(
            detailsRepository = FakeMissionDetailsRepository("details"),
            repository = repository,
            candidateProvider = candidateProvider,
            placeSelector = selector
        )
        val states = viewModel.observeMissionStates()

        try {
            viewModel.generateMission(44.4268, 26.1025, "Bucharest")
            advanceUntilIdle()

            val received = states.last() as MissionsViewModel.MissionState.MissionReceived
            assertTrue(received.text.contains("The Infinity Column"))
            assertEquals("The Infinity Column", repository.savedMission?.target)
            assertEquals(listOf(first, second), selector.requests.single().candidates)
            assertFalse(states.any { it is MissionsViewModel.MissionState.Error })
        } finally {
            store.clear()
            viewModel.missionState.removeObserver(states.observer)
        }
    }

    @Test
    fun `generateMission falls back when all candidates fail validation`() = runTest {
        val candidateProvider = FakeMissionCandidateProvider(
            candidates = listOf(MissionPlaceCandidate(name = "Missing Place", query = "Missing Place Bucharest Romania"))
        )
        val selector = FakeMissionPlaceSelector(
            result = MissionPlaceSelectionResult.Fallback("all candidates rejected")
        )
        val repository = TestWanderlyRepository(context = context, nearbyPlaces = emptyList())
        val (viewModel, store) = createViewModel(
            detailsRepository = FakeMissionDetailsRepository("details"),
            repository = repository,
            candidateProvider = candidateProvider,
            placeSelector = selector
        )
        val states = viewModel.observeMissionStates()

        try {
            viewModel.generateMission(44.4268, 26.1025, "Bucharest")
            advanceUntilIdle()

            val received = states.last() as MissionsViewModel.MissionState.MissionReceived
            assertTrue(received.text.contains("Could not verify a specific destination"))
            assertTrue(received.text.contains("Explore a nearby public place in Bucharest"))
            assertEquals(MissionsViewModel.FALLBACK_MISSION_TARGET, repository.savedMission?.target)
            assertEquals(44.4268, repository.savedMission?.targetLat)
            assertEquals(26.1025, repository.savedMission?.targetLng)
            assertEquals(0, repository.completeMissionCalls)
            assertFalse(states.any { it is MissionsViewModel.MissionState.Error })
        } finally {
            store.clear()
            viewModel.missionState.removeObserver(states.observer)
        }
    }

    @Test
    fun `generateMission falls back when candidate provider throws without surfacing raw error`() = runTest {
        val candidateProvider = FakeMissionCandidateProvider(error = IOException("places quota exhausted"))
        val repository = TestWanderlyRepository(context = context)
        val (viewModel, store) = createViewModel(
            detailsRepository = FakeMissionDetailsRepository("details"),
            repository = repository,
            candidateProvider = candidateProvider,
            placeSelector = FakeMissionPlaceSelector(MissionPlaceSelectionResult.Fallback("unused"))
        )
        val states = viewModel.observeMissionStates()

        try {
            viewModel.generateMission(44.4268, 26.1025, null)
            advanceUntilIdle()

            val received = states.last() as MissionsViewModel.MissionState.MissionReceived
            assertTrue(received.text.contains("Could not verify a specific destination"))
            assertTrue(received.text.contains("Explore your nearby area"))
            assertEquals(MissionsViewModel.FALLBACK_MISSION_TARGET, repository.savedMission?.target)
            assertFalse(states.any { it is MissionsViewModel.MissionState.Error })
        } finally {
            store.clear()
            viewModel.missionState.removeObserver(states.observer)
        }
    }

    @Test
    fun `generateMission tries deterministic selector fallback when ai returns no candidates`() = runTest {
        val candidateProvider = FakeMissionCandidateProvider(candidates = emptyList())
        val selector = FakeMissionPlaceSelector(
            result = MissionPlaceSelectionResult.Success(
                validatedPlace(
                    candidate = MissionPlaceCandidate(
                        name = "parks in Bucharest",
                        query = "parks in Bucharest Romania"
                    ),
                    name = "Cismigiu Gardens"
                )
            )
        )
        val repository = TestWanderlyRepository(context = context)
        val (viewModel, store) = createViewModel(
            detailsRepository = FakeMissionDetailsRepository("details"),
            repository = repository,
            candidateProvider = candidateProvider,
            placeSelector = selector
        )
        val states = viewModel.observeMissionStates()

        try {
            viewModel.generateMission(44.4268, 26.1025, "Bucharest")
            advanceUntilIdle()

            val received = states.last() as MissionsViewModel.MissionState.MissionReceived
            assertTrue(received.text.contains("Cismigiu Gardens"))
            assertEquals("Cismigiu Gardens", repository.savedMission?.target)
            assertTrue(selector.requests.single().candidates.isEmpty())
            assertFalse(states.any { it is MissionsViewModel.MissionState.Error })
        } finally {
            store.clear()
            viewModel.missionState.removeObserver(states.observer)
        }
    }

    @Test
    fun `fallback mission cannot complete before photo verification succeeds`() = runTest {
        val repository = TestWanderlyRepository(context = context)
        val (viewModel, store) = createViewModel(
            detailsRepository = FakeMissionDetailsRepository("details"),
            repository = repository,
            candidateProvider = FakeMissionCandidateProvider(candidates = emptyList()),
            placeSelector = FakeMissionPlaceSelector(MissionPlaceSelectionResult.Fallback("no places"))
        )
        val states = viewModel.observeMissionStates()

        try {
            viewModel.generateMission(44.4268, 26.1025, "Bucharest")
            advanceUntilIdle()

            viewModel.completeMission()
            advanceUntilIdle()

            assertEquals(0, repository.completeMissionCalls)
            assertTrue(states.last() is MissionsViewModel.MissionState.Error)
        } finally {
            store.clear()
            viewModel.missionState.removeObserver(states.observer)
        }
    }

    private fun createViewModel(
        detailsRepository: MissionDetailsRepository,
        repository: TestWanderlyRepository = TestWanderlyRepository(context),
        candidateProvider: MissionCandidateProvider = FakeMissionCandidateProvider(
            candidates = listOf(MissionPlaceCandidate(name = "Test Cafe", query = "Test Cafe Bucharest Romania"))
        ),
        placeSelector: MissionPlaceSelecting = FakeMissionPlaceSelector(
            MissionPlaceSelectionResult.Success(
                validatedPlace(
                    candidate = MissionPlaceCandidate(name = "Test Cafe", query = "Test Cafe Bucharest Romania"),
                    name = "Test Cafe"
                )
            )
        )
    ): Pair<MissionsViewModel, ViewModelStore> {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MissionsViewModel(
                    repository,
                    SavedStateHandle(),
                    ProfileStateProvider(repository),
                    detailsRepository,
                    candidateProvider,
                    placeSelector
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

    private class TestWanderlyRepository(
        context: Context,
        private val nearbyPlaces: List<String> = emptyList()
    ) : WanderlyRepository(context) {
        private val profileFlow = MutableStateFlow<Profile?>(null)
        var savedMission: SavedMission? = null
            private set
        var completeMissionCalls = 0
            private set

        override val currentProfile: StateFlow<Profile?> = profileFlow

        override suspend fun getCurrentProfile(): Profile? = profileFlow.value

        override suspend fun fetchNearbyPlaces(lat: Double, lng: Double, radius: Int): List<String> =
            nearbyPlaces

        override suspend fun getMissionHistory(): String = ""

        override suspend fun getMissionTarget(): String = "Test Cafe"

        override suspend fun getMissionCity(): String = "Bucharest"

        override suspend fun saveMissionData(
            text: String,
            target: String,
            history: String,
            city: String?,
            targetLat: Double,
            targetLng: Double
        ) {
            savedMission = SavedMission(text, target, history, city, targetLat, targetLng)
        }

        override suspend fun completeMission(): MissionCompletionResult {
            completeMissionCalls++
            return MissionCompletionResult.Completed(
                honey = 10,
                streakCount = 1,
                lastMissionDate = "2026-04-29",
                rewardHoney = 10,
                streakBonusHoney = 0
            )
        }
    }

    private data class SavedMission(
        val text: String,
        val target: String,
        val history: String,
        val city: String?,
        val targetLat: Double,
        val targetLng: Double
    )

    private class FakeMissionCandidateProvider(
        val candidates: List<MissionPlaceCandidate> = emptyList(),
        private val error: Exception? = null
    ) : MissionCandidateProvider {
        var calls = 0
            private set

        override suspend fun generateCandidates(
            city: String,
            latitude: Double,
            longitude: Double,
            radiusKm: Double,
            missionType: String
        ): List<MissionPlaceCandidate> {
            calls++
            error?.let { throw it }
            return candidates
        }
    }

    private class FakeMissionPlaceSelector(
        private val result: MissionPlaceSelectionResult
    ) : MissionPlaceSelecting {
        val requests = mutableListOf<Request>()

        override suspend fun selectBestMissionPlace(
            userLat: Double,
            userLng: Double,
            city: String,
            countryRegion: String?,
            missionType: String,
            candidates: List<MissionPlaceCandidate>
        ): MissionPlaceSelectionResult {
            requests += Request(userLat, userLng, city, countryRegion, missionType, candidates)
            return result
        }

        data class Request(
            val userLat: Double,
            val userLng: Double,
            val city: String,
            val countryRegion: String?,
            val missionType: String,
            val candidates: List<MissionPlaceCandidate>
        )
    }

    private companion object {
        fun validatedPlace(
            candidate: MissionPlaceCandidate,
            name: String,
            lat: Double = 44.427,
            lng: Double = 26.103
        ): ValidatedMissionPlace = ValidatedMissionPlace(
            originalCandidate = candidate,
            placesName = name,
            placesId = "places-$name",
            latitude = lat,
            longitude = lng,
            distanceMeters = 120.0,
            locality = "Bucharest",
            formattedAddress = "Bucharest, Romania",
            rating = 4.6,
            userRatingsTotal = 100,
            confidenceScore = 0.9
        )
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
