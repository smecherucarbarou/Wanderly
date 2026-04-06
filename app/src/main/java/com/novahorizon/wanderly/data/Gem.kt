package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

@Serializable
data class Gem(
    val name: String,
    val description: String,
    val location: String,
    val reason: String // Why it's a hidden gem
)
