package com.novahorizon.wanderly.ui.guide

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.novahorizon.wanderly.data.ai.AiAssistantException
import com.novahorizon.wanderly.data.ai.AiAssistantRepository
import com.novahorizon.wanderly.data.ai.AiChatMessage
import com.novahorizon.wanderly.data.ai.AiChatRole
import com.novahorizon.wanderly.data.ai.AiGuideContext
import com.novahorizon.wanderly.data.ai.AiQuotaResult
import com.novahorizon.wanderly.data.plus.PlusEntitlement
import com.novahorizon.wanderly.data.plus.PlusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WanderlyGuideViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state loads entitlement`() = runTest {
        val entitlement = activePlusEntitlement()
        val viewModel = WanderlyGuideViewModel(
            assistantRepository = FakeAiAssistantRepository(authenticated = true),
            plusRepository = FakePlusRepository(Result.success(entitlement)),
            locationProvider = FakeGuideLocationProvider()
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is WanderlyGuideUiState.Ready)
        assertEquals(entitlement, (state as WanderlyGuideUiState.Ready).entitlement)
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun `send message success adds user and assistant messages`() = runTest {
        val viewModel = WanderlyGuideViewModel(
            assistantRepository = FakeAiAssistantRepository(response = Result.success("Take the tram early, then walk the old center.")),
            plusRepository = FakePlusRepository(Result.success(PlusEntitlement.free())),
            locationProvider = FakeGuideLocationProvider()
        )
        advanceUntilIdle()

        viewModel.sendMessage("Plan a 2-day trip")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is WanderlyGuideUiState.Ready)
        val messages = (state as WanderlyGuideUiState.Ready).messages
        assertEquals(2, messages.size)
        assertEquals(AiChatRole.USER, messages[0].role)
        assertEquals("Plan a 2-day trip", messages[0].content)
        assertEquals(AiChatRole.ASSISTANT, messages[1].role)
        assertEquals("Take the tram early, then walk the old center.", messages[1].content)
    }

    @Test
    fun `quota exceeded moves to quota exceeded state`() = runTest {
        val quota = AiQuotaResult(
            allowed = false,
            isPlus = false,
            used = 5,
            limit = 5,
            remaining = 0,
            resetDate = "2026-05-14"
        )
        val viewModel = WanderlyGuideViewModel(
            assistantRepository = FakeAiAssistantRepository(
                response = Result.failure(AiAssistantException.QuotaExceeded(quota))
            ),
            plusRepository = FakePlusRepository(Result.success(PlusEntitlement.free())),
            locationProvider = FakeGuideLocationProvider()
        )
        advanceUntilIdle()

        viewModel.sendMessage("Rainy-day ideas")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is WanderlyGuideUiState.QuotaExceeded)
        assertEquals(quota, (state as WanderlyGuideUiState.QuotaExceeded).quota)
    }

    @Test
    fun `rapid duplicate sends only submit one assistant request`() = runTest {
        val assistantRepository = FakeAiAssistantRepository(
            response = Result.success("One answer")
        )
        val viewModel = WanderlyGuideViewModel(
            assistantRepository = assistantRepository,
            plusRepository = FakePlusRepository(Result.success(PlusEntitlement.free())),
            locationProvider = FakeGuideLocationProvider()
        )
        advanceUntilIdle()

        viewModel.sendMessage("Plan a weekend")
        viewModel.sendMessage("Plan a weekend")
        advanceUntilIdle()

        assertEquals(1, assistantRepository.sendCalls)
        val state = viewModel.uiState.value
        assertTrue(state is WanderlyGuideUiState.Ready)
        assertEquals(2, (state as WanderlyGuideUiState.Ready).messages.size)
    }

    @Test
    fun `quota exceeded state blocks more sends until refreshed`() = runTest {
        val quota = AiQuotaResult(
            allowed = false,
            isPlus = false,
            used = 5,
            limit = 5,
            remaining = 0,
            resetDate = "2026-05-14"
        )
        val assistantRepository = FakeAiAssistantRepository(
            response = Result.failure(AiAssistantException.QuotaExceeded(quota))
        )
        val viewModel = WanderlyGuideViewModel(
            assistantRepository = assistantRepository,
            plusRepository = FakePlusRepository(Result.success(PlusEntitlement.free())),
            locationProvider = FakeGuideLocationProvider()
        )
        advanceUntilIdle()

        viewModel.sendMessage("First")
        advanceUntilIdle()
        viewModel.sendMessage("Second")
        advanceUntilIdle()

        assertEquals(1, assistantRepository.sendCalls)
        val state = viewModel.uiState.value
        assertTrue(state is WanderlyGuideUiState.QuotaExceeded)
        assertEquals(1, (state as WanderlyGuideUiState.QuotaExceeded).messages.size)
    }

    @Test
    fun `unauthenticated user moves to unauthenticated state`() = runTest {
        val viewModel = WanderlyGuideViewModel(
            assistantRepository = FakeAiAssistantRepository(authenticated = false),
            plusRepository = FakePlusRepository(Result.success(PlusEntitlement.free())),
            locationProvider = FakeGuideLocationProvider()
        )

        advanceUntilIdle()

        assertEquals(WanderlyGuideUiState.Unauthenticated, viewModel.uiState.value)
    }

    @Test
    fun `backend error moves to error state`() = runTest {
        val viewModel = WanderlyGuideViewModel(
            assistantRepository = FakeAiAssistantRepository(
                response = Result.failure(AiAssistantException.BackendError())
            ),
            plusRepository = FakePlusRepository(Result.success(PlusEntitlement.free())),
            locationProvider = FakeGuideLocationProvider()
        )
        advanceUntilIdle()

        viewModel.sendMessage("Find cheap food nearby")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is WanderlyGuideUiState.Error)
        assertEquals(
            "Wanderly Guide is unavailable. Please try again.",
            (state as WanderlyGuideUiState.Error).message
        )
    }

    @Test
    fun `nearby message sends current approximate location context`() = runTest {
        val assistantRepository = FakeAiAssistantRepository(response = Result.success("Use your nearby lunch spots first."))
        val viewModel = WanderlyGuideViewModel(
            assistantRepository = assistantRepository,
            plusRepository = FakePlusRepository(Result.success(PlusEntitlement.free())),
            locationProvider = FakeGuideLocationProvider(
                GuideLocationContext(
                    city = "Bucharest",
                    adminArea = "Bucharest",
                    country = "Romania",
                    coarseCoordinates = "44.43, 26.10"
                )
            )
        )
        advanceUntilIdle()

        viewModel.sendMessage("Find cheap food nearby")
        advanceUntilIdle()

        assertEquals(
            AiGuideContext(
                approximateLocation = "near 44.43, 26.10",
                currentCity = "Bucharest",
                currentAdminArea = "Bucharest",
                currentCountry = "Romania",
                coarseCoordinates = "44.43, 26.10"
            ),
            assistantRepository.lastContext
        )
    }

    @Test
    fun `hidden gems sends named city context instead of coordinate only context`() = runTest {
        val assistantRepository = FakeAiAssistantRepository(response = Result.success("Stay inside Bucharest for hidden gems."))
        val viewModel = WanderlyGuideViewModel(
            assistantRepository = assistantRepository,
            plusRepository = FakePlusRepository(Result.success(PlusEntitlement.free())),
            locationProvider = FakeGuideLocationProvider(
                GuideLocationContext(
                    city = "Bucharest",
                    adminArea = "Bucharest",
                    country = "Romania",
                    coarseCoordinates = "44.43, 26.10"
                )
            )
        )
        advanceUntilIdle()

        viewModel.sendMessage("hidden gems")
        advanceUntilIdle()

        assertEquals(null, assistantRepository.lastContext?.approximateLocation)
        assertEquals("Bucharest", assistantRepository.lastContext?.currentCity)
        assertEquals("44.43, 26.10", assistantRepository.lastContext?.coarseCoordinates)
        assertEquals(
            "Current city: Bucharest, Romania. Recommend places inside this city only, not nearby towns or villages.",
            assistantRepository.lastContext?.tripContext
        )
    }

    @Test
    fun `hidden gems without confirmed city asks for city instead of sending coordinates`() = runTest {
        val assistantRepository = FakeAiAssistantRepository(response = Result.success("Which city should I use?"))
        val viewModel = WanderlyGuideViewModel(
            assistantRepository = assistantRepository,
            plusRepository = FakePlusRepository(Result.success(PlusEntitlement.free())),
            locationProvider = FakeGuideLocationProvider(
                GuideLocationContext(
                    city = null,
                    adminArea = "Gorj",
                    country = "Romania",
                    coarseCoordinates = "44.80, 23.00"
                )
            )
        )
        advanceUntilIdle()

        viewModel.sendMessage("hidden gems")
        advanceUntilIdle()

        assertEquals(null, assistantRepository.lastContext?.approximateLocation)
        assertEquals(null, assistantRepository.lastContext?.currentCity)
        assertEquals(null, assistantRepository.lastContext?.coarseCoordinates)
        assertEquals(
            "Ask the user which city to use before recommending hidden gems.",
            assistantRepository.lastContext?.tripContext
        )
    }

    private class FakeAiAssistantRepository(
        private val response: Result<String> = Result.success("Assistant reply"),
        private val authenticated: Boolean = true
    ) : AiAssistantRepository() {
        var lastContext: AiGuideContext? = null
            private set
        var sendCalls = 0
            private set

        override suspend fun isAuthenticated(): Boolean = authenticated

        override suspend fun sendMessage(
            message: String,
            history: List<AiChatMessage>,
            context: AiGuideContext
        ): Result<String> {
            sendCalls++
            lastContext = context
            return response
        }
    }

    private class FakeGuideLocationProvider(
        private val locationContext: GuideLocationContext? = null
    ) : GuideLocationProvider {
        override suspend fun getApproximateLocationContext(): GuideLocationContext? = locationContext
    }

    private class FakePlusRepository(
        private val entitlement: Result<PlusEntitlement>
    ) : PlusRepository() {
        override suspend fun getMyEntitlement(): Result<PlusEntitlement> = entitlement
    }

    private fun activePlusEntitlement(): PlusEntitlement =
        PlusEntitlement(
            isPlus = true,
            status = "active",
            provider = "manual",
            productId = "wanderly_plus_monthly",
            entitlement = "wanderly_plus",
            currentPeriodEnd = Instant.parse("2026-12-31T23:59:59Z")
        )
}
