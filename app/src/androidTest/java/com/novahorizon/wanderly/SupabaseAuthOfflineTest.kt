package com.novahorizon.wanderly

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SupabaseAuthOfflineTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<AuthActivity>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WanderlyGraph.setRepositoryForTesting(FakeWanderlyRepository(context))
        WanderlyGraph.setEmailAuthServiceForTesting(
            FakeSupabaseClient(IOException("network unavailable"))
        )
        WanderlyGraph.setMissionGenerationServiceForTesting(null)
        WanderlyGraph.setMissionLocationProviderForTesting(null)
        WanderlyGraph.setMissionCityResolverForTesting(null)
    }

    @After
    fun tearDown() {
        WanderlyGraph.resetTestOverrides()
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

    private class FakeSupabaseClient(private val error: IOException) : EmailAuthService {
        override suspend fun signInWithEmail(email: String, password: String) {
            throw error
        }
    }

    companion object {
        private const val RAW_NETWORK_ERROR = "network unavailable"
        private const val FRIENDLY_OFFLINE_ERROR =
            "No internet connection. Please check your network."
    }
}
