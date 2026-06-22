package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

/**
 * Outcome of attempting to discover a gem at the user's current location.
 * [TooFar] is decided client-side by the proximity gate; the rest map from the
 * `discover_gem_by_place` RPC response.
 */
sealed class GemDiscoveryResult {
    data class Success(val rewardHoney: Int) : GemDiscoveryResult()
    object AlreadyDiscovered : GemDiscoveryResult()
    object TooFar : GemDiscoveryResult()
    object Unauthenticated : GemDiscoveryResult()
    object Error : GemDiscoveryResult()
}

/** Proximity gate for gem discovery. A gem is discoverable only when the user is close enough. */
object GemProximity {
    const val DISCOVER_RADIUS_M = 120.0

    fun isWithinRange(distanceKm: Double): Boolean = distanceKm * 1000.0 <= DISCOVER_RADIUS_M
}

@Serializable
internal data class DiscoverGemRpcResponse(
    val success: Boolean,
    val error: String? = null,
    val reward_honey: Int? = null,
    val gem_id: String? = null,
    // The RPC returns 'gem_finder' on the first-ever discovery, else null. Modeled for completeness;
    // the first-gem toast is derived from the discovery count, not this field.
    val badge: String? = null
)

@Serializable
internal data class DiscoverGemParams(
    val p_name: String,
    val p_lat: Double,
    val p_lng: Double,
    val p_category: String? = null,
    val p_place_id: String? = null
)

internal fun mapDiscoverGemResponse(response: DiscoverGemRpcResponse): GemDiscoveryResult =
    if (response.success) {
        GemDiscoveryResult.Success(response.reward_honey ?: 0)
    } else when (response.error) {
        "already_discovered" -> GemDiscoveryResult.AlreadyDiscovered
        "not_authenticated" -> GemDiscoveryResult.Unauthenticated
        else -> GemDiscoveryResult.Error
    }
