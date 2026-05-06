package com.novahorizon.wanderly.ui.compose

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.novahorizon.wanderly.ui.compose.screens.onboarding.OnboardingScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `displays first page content and navigation`() {
        composeTestRule.setContent {
            WanderlyTheme {
                OnboardingScreen(onComplete = {})
            }
        }

        composeTestRule.onNodeWithText("Skip").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 / 3").assertIsDisplayed()
    }

    @Test
    fun `skip button triggers onComplete`() {
        var completed = false
        composeTestRule.setContent {
            WanderlyTheme {
                OnboardingScreen(onComplete = { completed = true })
            }
        }

        composeTestRule.onNodeWithText("Skip").performClick()

        assertTrue(completed)
    }

    @Test
    fun `next button advances to second page`() {
        composeTestRule.setContent {
            WanderlyTheme {
                OnboardingScreen(onComplete = {})
            }
        }

        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("2 / 3").assertIsDisplayed()
    }
}
