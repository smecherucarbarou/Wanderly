package com.novahorizon.wanderly

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.novahorizon.wanderly.api.GeminiClient
import com.novahorizon.wanderly.api.PlacesGeocoder
import com.novahorizon.wanderly.api.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

interface EmailAuthService {
    suspend fun signInWithEmail(email: String, password: String)
}

object SupabaseEmailAuthService : EmailAuthService {
    override suspend fun signInWithEmail(email: String, password: String) {
        SupabaseClient.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }
}

interface MissionGenerationService {
    suspend fun generateText(prompt: String): String
    suspend fun generateWithSearch(prompt: String): String
    suspend fun analyzeImage(bitmap: Bitmap, prompt: String): String
    suspend fun resolveCoordinates(
        placeName: String,
        targetCity: String,
        userLat: Double,
        userLng: Double,
        radiusKm: Double
    ): PlacesGeocoder.VerifiedPlace?
}

object DefaultMissionGenerationService : MissionGenerationService {
    override suspend fun generateText(prompt: String): String = GeminiClient.generateText(prompt)

    override suspend fun generateWithSearch(prompt: String): String =
        GeminiClient.generateWithSearch(prompt)

    override suspend fun analyzeImage(bitmap: Bitmap, prompt: String): String =
        GeminiClient.analyzeImage(bitmap, prompt)

    override suspend fun resolveCoordinates(
        placeName: String,
        targetCity: String,
        userLat: Double,
        userLng: Double,
        radiusKm: Double
    ): PlacesGeocoder.VerifiedPlace? {
        return PlacesGeocoder.resolveCoordinates(
            placeName = placeName,
            targetCity = targetCity,
            userLat = userLat,
            userLng = userLng,
            radiusKm = radiusKm
        )
    }
}

interface MissionLocationProvider {
    fun requestCurrentLocation(
        fragment: Fragment,
        onSuccess: (Location?) -> Unit,
        onFailure: (Exception) -> Unit
    )
}

object DefaultMissionLocationProvider : MissionLocationProvider {
    override fun requestCurrentLocation(
        fragment: Fragment,
        onSuccess: (Location?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(fragment.requireActivity())
        val context = fragment.requireContext()
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(onSuccess)
                .addOnFailureListener { error -> onFailure(Exception(error)) }
        } catch (e: SecurityException) {
            onFailure(e)
        }
    }
}

interface MissionCityResolver {
    suspend fun resolveCityName(context: Context, location: Location): String?
}

object DefaultMissionCityResolver : MissionCityResolver {
    override suspend fun resolveCityName(context: Context, location: Location): String? {
        val geocoder = Geocoder(context, Locale.getDefault())

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                    val cityName = addresses.firstOrNull()?.locality
                        ?: addresses.firstOrNull()?.adminArea
                    if (continuation.isActive) {
                        continuation.resume(cityName)
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) {
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()
                    ?.let { address -> address.locality ?: address.adminArea }
            }
        }
    }
}
