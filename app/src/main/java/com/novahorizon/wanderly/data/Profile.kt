package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val username: String? = null,
    val honey: Int? = 0,
    val hive_rank: Int? = 1,
    val badges: List<String>? = emptyList(),
    val cities_visited: List<String>? = emptyList(),
    val avatar_url: String? = null,
    val last_buzz_date: String? = null,
    val last_lat: Double? = null,
    val last_lng: Double? = null,
    val friend_code: String? = null,
    val streak_count: Int? = 0
)
