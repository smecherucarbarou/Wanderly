package com.novahorizon.wanderly.ui.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.novahorizon.wanderly.data.AuthRepository
import com.novahorizon.wanderly.ui.auth.AuthViewModel.AuthUiState
import com.novahorizon.wanderly.ui.auth.AuthViewModel.AuthEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

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
    fun `setLoading enters loading state`() = runTest {
        val viewModel = AuthViewModel(FakeAuthRepository())

        viewModel.setLoading()

        assertEquals(AuthUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `setError exposes provided message`() = runTest {
        val viewModel = AuthViewModel(FakeAuthRepository())
        val events = mutableListOf<AuthEvent>()
        val job = launch { viewModel.events.toList(events) }

        viewModel.setLoading()
        viewModel.setError("Google sign-in failed. Please try again.")
        advanceUntilIdle()

        assertEquals(AuthUiState.Idle, viewModel.uiState.value)
        assertTrue(events.any { it is AuthEvent.Error && it.message == "Google sign-in failed. Please try again." })
        job.cancel()
    }

    private class FakeAuthRepository : AuthRepository()
}
