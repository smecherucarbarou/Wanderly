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

/**
 * Lean projection for OTHER users (leaderboard, friends, friend-code lookup). It deliberately omits
 * every sensitive/server-owned column (last_lat/last_lng/admin_role/last_mission_date/streak_freezes),
 * so the social RPCs decode into this type and another user's row can never deserialize those fields.
 */
@Serializable
data class PublicProfile(
    val id: String,
    val username: String? = null,
    val honey: Int? = 0,
    val hive_rank: Int? = 1,
    val badges: List<String>? = emptyList(),
    val cities_visited: List<String>? = emptyList(),
    val avatar_url: String? = null,
    val friend_code: String? = null,
    val streak_count: Int? = 0,
    val explorer_class: String? = null,
    val equipped_frame: String? = null,
    val equipped_skin: String? = null,
    val equipped_widget_theme: String? = null
)

/** Adapts a [PublicProfile] to the UI's [Profile] type; sensitive/self-only fields stay null/default. */
fun PublicProfile.toProfile(): Profile = Profile(
    id = id,
    username = username,
    honey = honey,
    hive_rank = hive_rank,
    badges = badges,
    cities_visited = cities_visited,
    avatar_url = avatar_url,
    friend_code = friend_code,
    streak_count = streak_count,
    explorer_class = explorer_class,
    equipped_frame = equipped_frame,
    equipped_skin = equipped_skin,
    equipped_widget_theme = equipped_widget_theme
)
