package com.novahorizon.wanderly.ui.profile

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.novahorizon.wanderly.R
import com.novahorizon.wanderly.data.AvatarRepository
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.ProfileStateProvider
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AvatarUploadLargeImageTest {

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
    fun `large synthetic avatar upload emits user-friendly error`() = runTest {
        val largeBitmap = Bitmap.createBitmap(1400, 1400, Bitmap.Config.ARGB_8888)
        val repository = LargeAvatarRejectingRepository(context, largeBitmap)
        val (viewModel, store) = createViewModel(repository)
        val events = viewModel.observeProfileEvents()

        try {
            assertTrue(largeBitmap.byteCount > AvatarRepository.MAX_AVATAR_UPLOAD_BYTES)

            viewModel.uploadAvatar(testProfile(), Uri.parse("content://wanderly/large-avatar"))
            advanceUntilIdle()

            val event = events.last() as ProfileViewModel.ProfileEvent.ShowMessage
            assertEquals(UiText.StringResource(R.string.profile_avatar_upload_failed), event.message)
            assertTrue(event.isError)
        } finally {
            largeBitmap.recycle()
            store.clear()
            viewModel.profileEvent.removeObserver(events.observer)
        }
    }

    private fun createViewModel(
        repository: LargeAvatarRejectingRepository
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

    private fun ProfileViewModel.observeProfileEvents(): ObservedProfileEvents {
        val events = ObservedProfileEvents()
        profileEvent.observeForever(events.observer)
        return events
    }

    private class ObservedProfileEvents : ArrayList<ProfileViewModel.ProfileEvent?>() {
        val observer = Observer<ProfileViewModel.ProfileEvent?> { add(it) }
    }

    private class LargeAvatarRejectingRepository(
        context: Context,
        private val bitmap: Bitmap
    ) : WanderlyRepository(context) {
        private val profileFlow = MutableStateFlow<Profile?>(null)

        override val currentProfile: StateFlow<Profile?> = profileFlow

        override suspend fun getCurrentProfile(): Profile? = profileFlow.value

        override suspend fun uploadAvatar(uri: Uri, profileId: String): String {
            if (bitmap.byteCount > AvatarRepository.MAX_AVATAR_UPLOAD_BYTES) {
                throw IllegalArgumentException("raw avatar too large: ${bitmap.byteCount}")
            }
            return "profiles/$profileId/avatar.jpg"
        }
    }

    private fun testProfile(): Profile = Profile(
        id = "user-1",
        username = "Test Bee",
        badges = listOf("Early Bee")
    )
}
