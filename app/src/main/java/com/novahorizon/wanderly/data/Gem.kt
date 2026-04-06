package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

@Serializable
data class Gem(
    val name: String,
    val description: String,
    val location: String,
    val reason: String,
    val category: String = "place",
    val lat: Double = 0.0,
    val lng: Double = 0.0
)
