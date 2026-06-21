package com.novahorizon.wanderly.ui.guide

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

data class GuideLocationContext(
    val city: String?,
    val adminArea: String?,
    val country: String?,
    val coarseCoordinates: String?
) {
    fun cityDisplayName(): String? {
        val resolvedCity = city?.trim()?.takeIf(String::isNotBlank) ?: return null
        val parts = listOf(city, country)
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
        return (listOf(resolvedCity) + parts.filterNot { it == resolvedCity })
            .joinToString(", ")
    }
}

interface GuideLocationProvider {
    suspend fun getApproximateLocationContext(): GuideLocationContext?
}

class AndroidGuideLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) : GuideLocationProvider {

    override suspend fun getApproximateLocationContext(): GuideLocationContext? {
        if (!hasLocationPermission()) return null

        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val location = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).awaitOrNull()
                ?: client.lastLocation.awaitOrNull()?.takeIf { it.isRecentEnough() }
            location?.toGuideLocationContext()
        } catch (e: SecurityException) {
            logWarning("Guide location permission unavailable", e)
            null
        } catch (e: Exception) {
            logWarning("Guide location unavailable", e)
            null
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun com.google.android.gms.tasks.Task<Location>.awaitOrNull(): Location? =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { location ->
                if (continuation.isActive) {
                    continuation.resume(location)
                }
            }
            addOnFailureListener {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
            addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }

    private suspend fun Location.toGuideLocationContext(): GuideLocationContext {
        val address = resolveAddress(this)
        return GuideLocationContext(
            city = address?.locality.cleanedPlaceLabel(),
            adminArea = address?.adminArea.cleanedPlaceLabel(),
            country = address?.countryName.cleanedPlaceLabel(),
            coarseCoordinates = String.format(
                Locale.US,
                "%.2f, %.2f",
                latitude,
                longitude
            )
        )
    }

    private suspend fun resolveAddress(location: Location): Address? =
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 5) { addresses ->
                        if (continuation.isActive) {
                            continuation.resume(addresses.bestCityAddress())
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                withContext(Dispatchers.IO) {
                    geocoder.getFromLocation(location.latitude, location.longitude, 5)
                        ?.bestCityAddress()
                }
            }
        } catch (e: Exception) {
            logWarning("Guide reverse geocode unavailable", e)
            null
        }

    private fun Location.isRecentEnough(): Boolean =
        time > 0L && System.currentTimeMillis() - time <= MAX_LAST_LOCATION_AGE_MS

    private fun List<Address>.bestCityAddress(): Address? =
        firstOrNull { !it.locality.cleanedPlaceLabel().isNullOrBlank() }
            ?: firstOrNull { !it.adminArea.cleanedPlaceLabel().isNullOrBlank() }
            ?: firstOrNull()

    private fun String?.cleanedPlaceLabel(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val collapsed = value.replace(Regex("\\s+"), " ").trim()
        val normalized = Normalizer.normalize(collapsed, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)

        val prefixes = listOf("municipiul ", "municipiu ", "orasul ", "oras ", "judetul ", "judet ", "comuna ")
        val matchedPrefix = prefixes.firstOrNull { normalized.startsWith(it) }
            ?: return collapsed.trim(',', '.', '-', ' ')
        return collapsed.drop(matchedPrefix.length).trim(',', '.', '-', ' ').ifBlank { null }
    }

    private fun logWarning(message: String, throwable: Throwable) {
        AppLogger.w(
            TAG,
            "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
        )
    }

    private companion object {
        const val TAG = "GuideLocationProvider"
        const val MAX_LAST_LOCATION_AGE_MS = 10 * 60 * 1000L
    }
}
