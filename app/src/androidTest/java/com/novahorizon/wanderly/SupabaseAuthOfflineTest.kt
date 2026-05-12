package com.novahorizon.wanderly

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novahorizon.wanderly.data.AuthRepository
import com.novahorizon.wanderly.di.RepositoryModule
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import javax.inject.Singleton

@HiltAndroidTest
@UninstallModules(RepositoryModule::class)
@RunWith(AndroidJUnit4::class)
class SupabaseAuthOfflineTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<AuthActivity>()

    @Module
    @InstallIn(SingletonComponent::class)
    object FakeRepositoryModule {
        @Provides
        @Singleton
        fun provideAuthRepository(): AuthRepository = object : AuthRepository() {
            override suspend fun signInWithEmail(email: String, password: String) {
                throw IOException("network unavailable")
            }
        }

        @Provides
        @Singleton
        fun provideWanderlyRepository(
            @dagger.hilt.android.qualifiers.ApplicationContext context: Context
        ): com.novahorizon.wanderly.data.WanderlyRepository =
            FakeWanderlyRepository(context)

        @Provides
        @Singleton
        fun provideProfileStateProvider(
            repository: com.novahorizon.wanderly.data.WanderlyRepository
        ): com.novahorizon.wanderly.data.ProfileStateProvider =
            com.novahorizon.wanderly.data.ProfileStateProvider(repository)

        @Provides
        @Singleton
        fun providePreferencesStore(
            @dagger.hilt.android.qualifiers.ApplicationContext context: Context
        ): com.novahorizon.wanderly.data.PreferencesStore =
            com.novahorizon.wanderly.data.PreferencesStore(context)

        @Provides
        @Singleton
        fun provideMissionDetailsRepository(): com.novahorizon.wanderly.data.MissionDetailsRepository =
            com.novahorizon.wanderly.data.MissionDetailsRepository()

        @Provides
        @Singleton
        fun provideEmailAuthService(): EmailAuthService = SupabaseEmailAuthService

        @Provides
        @Singleton
        fun provideMissionGenerationService(): MissionGenerationService = DefaultMissionGenerationService

        @Provides
        @Singleton
        fun provideMissionLocationProvider(): MissionLocationProvider = DefaultMissionLocationProvider

        @Provides
        @Singleton
        fun provideMissionCityResolver(): MissionCityResolver = DefaultMissionCityResolver

        @Provides
        fun provideLogoutCoordinator(
            @dagger.hilt.android.qualifiers.ApplicationContext context: Context,
            authRepository: AuthRepository,
            repository: com.novahorizon.wanderly.data.WanderlyRepository
        ): com.novahorizon.wanderly.data.LogoutCoordinator =
            com.novahorizon.wanderly.data.LogoutCoordinator.create(
                context = context,
                authRepository = authRepository,
                repository = repository,
                workManager = androidx.work.WorkManager.getInstance(context)
            )
    }

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun offlineLoginShowsFriendlyError() {
        val credentials = AndroidTestCredentialProvider.requireCredentials()

        composeTestRule.waitUntil(timeoutMillis = 7_000) {
            composeTestRule.onAllNodes(hasText("Email")).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText("Email").performTextInput(credentials.email)
        composeTestRule.onNodeWithText("Password").performTextInput(credentials.password)
        composeTestRule.onNodeWithText("Log In").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(hasText(FRIENDLY_OFFLINE_ERROR))
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithText(FRIENDLY_OFFLINE_ERROR).assertExists()
        composeTestRule.onNodeWithText(RAW_NETWORK_ERROR).assertDoesNotExist()
    }

    companion object {
        private const val RAW_NETWORK_ERROR = "network unavailable"
        private const val FRIENDLY_OFFLINE_ERROR =
            "No internet connection. Please check your network."
    }
}
