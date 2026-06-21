package com.novahorizon.wanderly.ui.main

import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.SensitiveProfileMutationResult
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainViewModelTest {

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
    fun `rejected hard lost reset does not announce streak lost`() = runTest {
        val repository = FakeWanderlyRepository(
            context = context,
            acceptResult = SensitiveProfileMutationResult.Rejected("not_hard_lost")
        )
        val viewModel = MainViewModel(repository)
        val messages = mutableListOf<String?>()
        val observer = Observer<String?> { messages += it }
        viewModel.streakMessage.observeForever(observer)

        try {
            viewModel.checkDailyStreak()
            advanceUntilIdle()

            assertFalse(messages.filterNotNull().any { it.contains("Streak lost", ignoreCase = true) })
        } finally {
            viewModel.streakMessage.removeObserver(observer)
        }
    }

    private class FakeWanderlyRepository(
        context: Context,
        private val acceptResult: SensitiveProfileMutationResult
    ) : WanderlyRepository(context) {
        private val profileFlow = MutableStateFlow(
            Profile(
                id = "user-1",
                honey = 250,
                streak_count = 12,
                last_mission_date = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(3).toString()
            )
        )

        override val currentProfile: StateFlow<Profile?> = profileFlow

        override suspend fun getCurrentProfile(): Profile? = profileFlow.value

        override suspend fun acceptStreakLoss(): SensitiveProfileMutationResult = acceptResult
    }
}
