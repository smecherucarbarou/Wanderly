package com.novahorizon.wanderly.data

data class DiscoveredPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val category: String,
    val areaLabel: String? = null,
    val source: String = "unknown",
    val rating: Double? = null,
    val reviewCount: Int? = null
)
