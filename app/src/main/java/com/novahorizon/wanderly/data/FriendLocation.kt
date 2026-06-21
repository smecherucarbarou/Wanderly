package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

/**
 * Friend coordinates exposed only through the `get_friend_locations()` RPC (accepted friends).
 * Kept separate from [Profile] because Profile no longer carries lat/lng after the 2026-06-15 migration.
 */
@Serializable
data class FriendLocation(
    val id: String,
    val username: String? = null,
    val avatar_url: String? = null,
    val last_lat: Double? = null,
    val last_lng: Double? = null
)
