package com.novahorizon.wanderly.ui.compose

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.MutableLiveData
import com.novahorizon.wanderly.data.AuthRepository
import com.novahorizon.wanderly.ui.auth.AuthViewModel
import com.novahorizon.wanderly.ui.compose.screens.auth.LoginScreen
import com.novahorizon.wanderly.ui.compose.theme.WanderlyTheme
import org.junit.Rule
import org.junit.Test

class LoginScreenComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createIdleViewModel(): AuthViewModel {
        val repo = object : AuthRepository() {
            override suspend fun signInWithEmail(email: String, password: String) {}
        }
        return AuthViewModel(repo)
    }

    @Test
    fun loginScreen_displaysEmailAndPasswordFields() {
        composeTestRule.setContent {
            WanderlyTheme {
                LoginScreen(
                    viewModel = createIdleViewModel(),
                    onNavigateToSignup = {},
                    onGoogleSignIn = {},
                    onLoginSuccess = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Email").assertIsDisplayed()
        composeTestRule.onNodeWithText("Password").assertIsDisplayed()
        composeTestRule.onNodeWithText("Log In").assertIsDisplayed()
    }

    @Test
    fun loginScreen_showsValidationErrorOnEmptySubmit() {
        composeTestRule.setContent {
            WanderlyTheme {
                LoginScreen(
                    viewModel = createIdleViewModel(),
                    onNavigateToSignup = {},
                    onGoogleSignIn = {},
                    onLoginSuccess = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Log In").performClick()

        composeTestRule.onNodeWithText("Enter a valid email").assertIsDisplayed()
        composeTestRule.onNodeWithText("Password is required").assertIsDisplayed()
    }

    @Test
    fun loginScreen_showsEmailErrorForInvalidFormat() {
        composeTestRule.setContent {
            WanderlyTheme {
                LoginScreen(
                    viewModel = createIdleViewModel(),
                    onNavigateToSignup = {},
                    onGoogleSignIn = {},
                    onLoginSuccess = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Email").performTextInput("notanemail")
        composeTestRule.onNodeWithText("Password").performTextInput("pass123")
        composeTestRule.onNodeWithText("Log In").performClick()

        composeTestRule.onNodeWithText("Enter a valid email").assertIsDisplayed()
    }

    @Test
    fun loginScreen_navigateToSignupButtonExists() {
        var navigated = false
        composeTestRule.setContent {
            WanderlyTheme {
                LoginScreen(
                    viewModel = createIdleViewModel(),
                    onNavigateToSignup = { navigated = true },
                    onGoogleSignIn = {},
                    onLoginSuccess = {}
                )
            }
        }

        composeTestRule.onNodeWithText("New bee? Join the hive!").performClick()
        assert(navigated)
    }

    @Test
    fun loginScreen_googleSignInButtonExists() {
        composeTestRule.setContent {
            WanderlyTheme {
                LoginScreen(
                    viewModel = createIdleViewModel(),
                    onNavigateToSignup = {},
                    onGoogleSignIn = {},
                    onLoginSuccess = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Continue with Google").assertIsDisplayed()
    }

    @Test
    fun loginScreen_showsLoadingWhenAuthStateLoading() {
        val repo = object : AuthRepository() {
            override suspend fun signInWithEmail(email: String, password: String) {
                kotlinx.coroutines.delay(10_000)
            }
        }
        val vm = AuthViewModel(repo)

        composeTestRule.setContent {
            WanderlyTheme {
                LoginScreen(
                    viewModel = vm,
                    onNavigateToSignup = {},
                    onGoogleSignIn = {},
                    onLoginSuccess = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Email").performTextInput("test@example.com")
        composeTestRule.onNodeWithText("Password").performTextInput("password123")
        composeTestRule.onNodeWithText("Log In").performClick()

        composeTestRule.waitUntil(timeoutMillis = 2000) {
            composeTestRule.onAllNodes(hasText("Log In")).fetchSemanticsNodes().isEmpty()
        }
    }
}
