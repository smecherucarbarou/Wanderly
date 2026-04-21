package com.novahorizon.wanderly

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.Location
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.novahorizon.wanderly.api.PlacesGeocoder
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MissionGenerationInstrumentedTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private lateinit var fakeRepository: FakeWanderlyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fakeRepository = FakeWanderlyRepository(context)

        WanderlyGraph.setRepositoryForTesting(fakeRepository)
        WanderlyGraph.setMissionGenerationServiceForTesting(
            FakeMissionGenerationService(
                missionJson = """{"missionText":"Go to Test Cafe and take a photo of the sign or entrance.","targetName":"Test Cafe"}""",
                verifiedPlace = PlacesGeocoder.VerifiedPlace(
                    lat = 44.4268,
                    lng = 26.1025,
                    name = "Test Cafe",
                    formattedAddress = "123 Test Street"
                )
            )
        )
        WanderlyGraph.setMissionLocationProviderForTesting(
            FakeMissionLocationProvider(
                Location("test").apply {
                    latitude = 44.4268
                    longitude = 26.1025
                }
            )
        )
        WanderlyGraph.setMissionCityResolverForTesting(FakeMissionCityResolver("Bucharest"))

        Intents.init()
        intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWith(
            android.app.Instrumentation.ActivityResult(Activity.RESULT_OK, Intent())
        )
    }

    @After
    fun tearDown() {
        Intents.release()
        WanderlyGraph.resetTestOverrides()
    }

    @Test
    fun generatingMissionShowsMissionTextAndLaunchesCameraIntent() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(isRoot()).perform(waitFor(visibleViewMatcher(R.id.bottom_navigation), 7_000L))
            onView(withId(R.id.missionsFragment)).perform(click())
            onView(isRoot()).perform(waitFor(visibleViewMatcher(R.id.new_flight_button), 5_000L))
            onView(withId(R.id.new_flight_button)).perform(scrollTo(), click())

            onView(isRoot()).perform(waitFor(withText(EXPECTED_MISSION_TEXT), 5_000L))
            onView(withId(R.id.mission_card)).check(matches(isDisplayed()))
            onView(withId(R.id.mission_text)).check(matches(withText(EXPECTED_MISSION_TEXT)))

            onView(isRoot()).perform(waitFor(visibleViewMatcher(R.id.verify_button), 5_000L))
            onView(withId(R.id.verify_button)).perform(scrollTo(), click())

            intended(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
        }
    }

    companion object {
        private const val EXPECTED_MISSION_TEXT =
            "Go to Test Cafe and take a photo of the sign or entrance."
    }
}
