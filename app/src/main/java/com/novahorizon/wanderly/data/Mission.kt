package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

@Serializable
data class Mission(
    val id: String? = null,
    val user_id: String,
    val text: String,
    val location_lat: Double,
    val location_lng: Double,
    val city: String,
    val completed: Boolean = false
)
