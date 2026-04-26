package com.novahorizon.wanderly

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.view.View
import androidx.test.espresso.PerformException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import com.novahorizon.wanderly.api.PlacesGeocoder
import com.novahorizon.wanderly.data.Profile
import com.novahorizon.wanderly.data.WanderlyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import java.util.concurrent.TimeoutException

class FakeWanderlyRepository(
    context: Context,
    initialProfile: Profile = defaultTestProfile()
) : WanderlyRepository(context) {

    private val profileFlow = MutableStateFlow<Profile?>(initialProfile)
    private var missionHistoryValue: String = ""
    private var missionTextValue: String? = null
    private var missionTargetValue: String? = null
    private var missionCityValue: String? = null

    override val currentProfile: StateFlow<Profile?> = profileFlow

    override suspend fun getCurrentProfile(): Profile? = profileFlow.value

    override suspend fun updateProfile(profile: Profile): Boolean {
        profileFlow.value = profile
        return true
    }

    override suspend fun fetchNearbyPlaces(lat: Double, lng: Double, radius: Int): List<String> {
        return listOf("Test Cafe")
    }

    override suspend fun getFriends(): List<Profile> = emptyList()

    override suspend fun getMissionHistory(): String = missionHistoryValue

    override suspend fun saveMissionData(
        text: String,
        target: String,
        history: String,
        city: String?,
        targetLat: Double,
        targetLng: Double
    ) {
        missionTextValue = text
        missionTargetValue = target
        missionCityValue = city
        missionHistoryValue = history
    }

    override suspend fun isOnboardingSeen(): Boolean = true

    fun lastSavedMissionText(): String? = missionTextValue

    fun lastSavedMissionTarget(): String? = missionTargetValue

    fun lastSavedMissionCity(): String? = missionCityValue
}

class FakeEmailAuthService : EmailAuthService {
    override suspend fun signInWithEmail(email: String, password: String) = Unit
}

class FakeMissionGenerationService(
    private val missionJson: String,
    private val verifiedPlace: PlacesGeocoder.VerifiedPlace
) : MissionGenerationService {
    override suspend fun generateText(prompt: String): String = missionJson

    override suspend fun generateWithSearch(prompt: String): String = missionJson

    override suspend fun analyzeImage(bitmap: Bitmap, prompt: String): String = "YES: Verified."

    override suspend fun resolveCoordinates(
        placeName: String,
        targetCity: String,
        userLat: Double,
        userLng: Double,
        radiusKm: Double
    ): PlacesGeocoder.VerifiedPlace = verifiedPlace
}

class FakeMissionLocationProvider(private val location: Location) : MissionLocationProvider {
    override fun requestCurrentLocation(
        fragment: androidx.fragment.app.Fragment,
        onSuccess: (Location?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        onSuccess(location)
    }
}

class FakeMissionCityResolver(private val cityName: String?) : MissionCityResolver {
    override suspend fun resolveCityName(context: Context, location: Location): String? = cityName
}

fun waitFor(matcher: Matcher<View>, timeoutMs: Long = 5_000L): ViewAction {
    return object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()

        override fun getDescription(): String = "wait for view matching $matcher during $timeoutMs ms"

        override fun perform(uiController: UiController, view: View) {
            val deadline = System.currentTimeMillis() + timeoutMs
            do {
                val matchingView = androidx.test.espresso.util.TreeIterables
                    .breadthFirstViewTraversal(view)
                    .firstOrNull { candidate -> matcher.matches(candidate) }
                if (matchingView != null) {
                    return
                }
                uiController.loopMainThreadForAtLeast(50)
            } while (System.currentTimeMillis() < deadline)

            throw PerformException.Builder()
                .withCause(TimeoutException("Timed out waiting for $matcher"))
                .withViewDescription(view.toString())
                .build()
        }
    }
}

fun visibleViewMatcher(viewId: Int): Matcher<View> = allOf(
    androidx.test.espresso.matcher.ViewMatchers.withId(viewId),
    androidx.test.espresso.matcher.ViewMatchers.isDisplayed()
)

fun defaultTestProfile(): Profile = Profile(
    id = "test-user",
    username = "Test Bee",
    honey = 120,
    hive_rank = 2,
    friend_code = "ABC123",
    last_mission_date = "2026-04-21",
    streak_count = 3,
    last_lat = 44.4268,
    last_lng = 26.1025
)
