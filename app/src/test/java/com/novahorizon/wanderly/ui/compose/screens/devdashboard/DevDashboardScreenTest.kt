package com.novahorizon.wanderly.ui.compose.screens.devdashboard

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DevDashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `groups diagnostics into release-safe sections`() {
        composeTestRule.setContent {
            WanderlyTheme {
                DevDashboardScreen(
                    diagnostics = DevDashboardDiagnostics(
                        sections = listOf(
                            DevDashboardSection(
                                title = "Auth/session",
                                rows = listOf(DevDashboardRow("Auth", "profile loaded"))
                            ),
                            DevDashboardSection(
                                title = "Streak/notifications",
                                rows = listOf(DevDashboardRow("Notification permission", "denied"))
                            )
                        )
                    ),
                    isCrashlyticsEnabled = false,
                    callbacks = DevDashboardCallbacks(
                        onRefreshDiagnostics = {},
                        onOpenNotificationSettings = {},
                        onClearNotificationState = {},
                        onCrashlyticsNonfatal = {}
                    )
                )
            }
        }

        composeTestRule.onNodeWithText("Auth/session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Streak/notifications").assertIsDisplayed()
        composeTestRule.onNodeWithText("Notification permission").assertIsDisplayed()
    }
}
