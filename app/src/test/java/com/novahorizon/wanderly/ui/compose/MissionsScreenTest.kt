package com.novahorizon.wanderly.ui.compose

import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.novahorizon.wanderly.DefaultMissionGenerationService
import com.novahorizon.wanderly.MissionGenerationService
import com.novahorizon.wanderly.data.MissionCompletionResult
import com.novahorizon.wanderly.data.MissionDetailsRepository
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileStateProvider
import com.novahorizon.wanderly.data.WanderlyRepository
import com.novahorizon.wanderly.data.mission.MissionCandidateProvider
import com.novahorizon.wanderly.data.mission.MissionPlaceCandidate
import com.novahorizon.wanderly.data.mission.MissionPlaceSelectionResult
import com.novahorizon.wanderly.data.mission.MissionPlaceSelecting
import com.novahorizon.wanderly.ui.compose.screens.missions.MissionsScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import com.novahorizon.wanderly.ui.missions.MissionsViewModel
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
class MissionsScreenTest {

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
    fun `shows generate mission button in idle state`() {
        val (viewModel, store) = createViewModel()

        try {
            composeTestRule.setContent {
                WanderlyTheme {
                    MissionsScreen(
                        viewModel = viewModel,
                        onGenerateMission = {},
                        onVerifyPhoto = {},
                        onCompleteMission = {},
                        onLearnMore = {}
                    )
                }
            }

            composeTestRule.onNodeWithText("Find a mission").assertIsDisplayed()
        } finally {
            store.clear()
        }
    }

    @Test
    fun `shows honey counter`() {
        val (viewModel, store) = createViewModel()

        try {
            composeTestRule.setContent {
                WanderlyTheme {
                    MissionsScreen(
                        viewModel = viewModel,
                        onGenerateMission = {},
                        onVerifyPhoto = {},
                        onCompleteMission = {},
                        onLearnMore = {}
                    )
                }
            }

            composeTestRule.onNodeWithContentDescription("0 honey").assertIsDisplayed()
        } finally {
            store.clear()
        }
    }

    @Test
    fun `shows streak fire pill even when streak is zero`() {
        val (viewModel, store) = createViewModel()

        try {
            composeTestRule.setContent {
                WanderlyTheme {
                    MissionsScreen(
                        viewModel = viewModel,
                        onGenerateMission = {},
                        onVerifyPhoto = {},
                        onCompleteMission = {},
                        onLearnMore = {}
                    )
                }
            }

            composeTestRule.onNodeWithContentDescription("0 day streak").assertIsDisplayed()
        } finally {
            store.clear()
        }
    }

    private fun createViewModel(): Pair<MissionsViewModel, ViewModelStore> {
        val repository = object : WanderlyRepository(context) {
            private val profileFlow = MutableStateFlow<Profile?>(null)
            override val currentProfile: StateFlow<Profile?> = profileFlow
            override suspend fun getCurrentProfile(): Profile? = null
            override suspend fun getMissionHistory(): String = ""
            override suspend fun getMissionTarget(): String = "Test"
            override suspend fun getMissionCity(): String = "Bucharest"
            override suspend fun completeMission() = MissionCompletionResult.Unauthenticated
        }
        val candidateProvider = object : MissionCandidateProvider {
            override suspend fun generateCandidates(
                city: String, latitude: Double, longitude: Double,
                radiusKm: Double, missionType: String
            ) = emptyList<MissionPlaceCandidate>()
        }
        val placeSelector = object : MissionPlaceSelecting {
            override suspend fun selectBestMissionPlace(
                userLat: Double, userLng: Double, city: String,
                countryRegion: String?, missionType: String,
                candidates: List<MissionPlaceCandidate>
            ) = MissionPlaceSelectionResult.Fallback("test")
        }
        val detailsRepo = object : MissionDetailsRepository() {
            override suspend fun getPlaceDetails(placeName: String, targetCity: String) = ""
        }

        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MissionsViewModel(
                    repository,
                    SavedStateHandle(),
                    ProfileStateProvider(repository),
                    detailsRepo,
                    candidateProvider,
                    placeSelector,
                    DefaultMissionGenerationService
                ) as T
            }
        }
        return ViewModelProvider(store, factory)[MissionsViewModel::class.java] to store
    }
}
