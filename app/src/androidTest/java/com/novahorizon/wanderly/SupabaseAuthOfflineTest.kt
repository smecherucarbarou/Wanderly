package com.novahorizon.wanderly

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SupabaseAuthOfflineTest {

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

        ActivityScenario.launch(AuthActivity::class.java).use {
            onView(isRoot()).perform(waitFor(visibleViewMatcher(R.id.email_input), 7_000L))

            onView(withId(R.id.email_input)).perform(typeText(credentials.email), closeSoftKeyboard())
            onView(withId(R.id.password_input)).perform(typeText(credentials.password), closeSoftKeyboard())
            onView(withId(R.id.login_button)).perform(scrollTo(), click())

            onView(withText(FRIENDLY_OFFLINE_ERROR)).check(matches(isDisplayed()))
            onView(withText(RAW_NETWORK_ERROR)).check(doesNotExist())
            onView(withText("java.io.IOException: $RAW_NETWORK_ERROR")).check(doesNotExist())
        }
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
