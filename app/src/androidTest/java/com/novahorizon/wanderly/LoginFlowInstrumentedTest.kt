package com.novahorizon.wanderly

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novahorizon.wanderly.auth.SessionNavigator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class LoginFlowInstrumentedTest {

    private lateinit var fakeRepository: FakeWanderlyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fakeRepository = FakeWanderlyRepository(
            context = context,
            initialProfile = defaultTestProfile().copy(last_mission_date = "2026-04-21")
        )
        WanderlyGraph.setRepositoryForTesting(fakeRepository)
        WanderlyGraph.setEmailAuthServiceForTesting(FakeEmailAuthService())
        WanderlyGraph.setMissionGenerationServiceForTesting(null)
        WanderlyGraph.setMissionLocationProviderForTesting(null)
        WanderlyGraph.setMissionCityResolverForTesting(null)
        SessionNavigator.setOpenMainOverrideForTesting { activity ->
            activity.startActivity(android.content.Intent(activity, MainActivity::class.java))
        }
    }

    @After
    fun tearDown() {
        SessionNavigator.resetTestOverrides()
        WanderlyGraph.resetTestOverrides()
    }

    @Test
    fun loginWithValidCredentialsOpensMainActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mainActivityMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)

        ActivityScenario.launch(AuthActivity::class.java).use {
            onView(isRoot()).perform(waitFor(visibleViewMatcher(R.id.email_input), 7_000L))

            onView(withId(R.id.email_input)).perform(typeText(TEST_EMAIL), closeSoftKeyboard())
            onView(withId(R.id.password_input)).perform(typeText(TEST_PASSWORD), closeSoftKeyboard())
            onView(withId(R.id.login_button)).perform(scrollTo(), click())

            val launchedMainActivity = instrumentation.waitForMonitorWithTimeout(mainActivityMonitor, 5_000L)
            assertNotNull("Expected MainActivity to be launched after login.", launchedMainActivity)

            val bottomNavigationVisible = AtomicBoolean(false)
            instrumentation.runOnMainSync {
                val bottomNavigation = launchedMainActivity?.findViewById<android.view.View>(R.id.bottom_navigation)
                bottomNavigationVisible.set(bottomNavigation?.isShown == true)
            }
            assertTrue("Expected MainActivity bottom navigation to be visible.", bottomNavigationVisible.get())

            launchedMainActivity?.finish()
        }

        instrumentation.removeMonitor(mainActivityMonitor)
    }

    @Test
    fun loginWithBlankPasswordShowsErrorSnackbar() {
        ActivityScenario.launch(AuthActivity::class.java).use {
            onView(isRoot()).perform(waitFor(visibleViewMatcher(R.id.email_input), 7_000L))

            onView(withId(R.id.email_input)).perform(typeText(TEST_EMAIL), closeSoftKeyboard())
            onView(withId(R.id.login_button)).perform(scrollTo(), click())

            onView(withText(R.string.auth_password_required)).check(matches(isDisplayed()))
        }
    }

    companion object {
        const val TEST_EMAIL = "mihaileon55@gmail.com"
        const val TEST_PASSWORD = "Carbarou123"
    }
}
