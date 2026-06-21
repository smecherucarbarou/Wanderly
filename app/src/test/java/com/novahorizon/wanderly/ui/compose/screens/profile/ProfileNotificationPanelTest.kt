package com.novahorizon.wanderly.ui.compose.screens.profile

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProfileNotificationPanelTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `shows notification status and action`() {
        var clicked = false

        composeTestRule.setContent {
            WanderlyTheme {
                ProfileNotificationPanel(
                    statusText = "Notifications off",
                    actionLabel = "Enable notifications",
                    onAction = { clicked = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Streak alerts are off").assertIsDisplayed()
        composeTestRule.onNodeWithText("Notifications off").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enable notifications").performClick()

        assertTrue(clicked)
    }
}
