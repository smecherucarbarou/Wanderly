package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val username: String? = null,
    val honey: Int? = 0,
    val hive_rank: Int? = 1,
    val admin_role: Boolean = false,
    val badges: List<String>? = emptyList(),
    val cities_visited: List<String>? = emptyList(),
    val avatar_url: String? = null,
    val last_mission_date: String? = null,
    val last_lat: Double? = null,
    val last_lng: Double? = null,
    val friend_code: String? = null,
    val streak_count: Int? = 0,
    val explorer_class: String? = null,
    val streak_freezes: Int? = 0,
    val equipped_frame: String? = null,
    val equipped_skin: String? = null,
    val equipped_widget_theme: String? = null
)

/**
 * Columns the client is allowed to read directly from `profiles` after the 2026-06-15 migration.
 * Hidden server-owned columns (last_lat/last_lng/last_mission_date/last_buzz_date/updated_at/admin_role)
 * are intentionally excluded; their values come from RPC results or local state instead.
 */
val PROFILE_VISIBLE_COLUMNS: List<String> = listOf(
    "id",
    "username",
    "honey",
    "hive_rank",
    "badges",
    "cities_visited",
    "avatar_url",
    "friend_code",
    "streak_count",
    "explorer_class",
    "streak_freezes",
    "equipped_frame",
    "equipped_skin",
    "equipped_widget_theme"
)
