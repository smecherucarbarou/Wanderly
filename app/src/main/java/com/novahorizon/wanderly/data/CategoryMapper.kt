package com.novahorizon.wanderly.data

import org.json.JSONObject

internal object CategoryMapper {
    fun fromOverpassTags(tags: JSONObject): String {
        val amenity = tags.optString("amenity")
        val tourism = tags.optString("tourism")
        val historic = tags.optString("historic")
        val leisure = tags.optString("leisure")

        return when {
            amenity in setOf("cafe", "restaurant") -> "Food"
            amenity in setOf("pub", "bar") -> "Drinks"
            tourism in setOf("museum", "gallery", "artwork") || historic.isNotBlank() -> "Culture"
            tourism in setOf("viewpoint", "attraction") || leisure in setOf("park", "garden") -> "Viewpoint"
            else -> "Culture"
        }
    }

    fun fromGoogleTypes(types: Set<String>): String {
        return when {
            types.any { it in setOf("cafe", "restaurant", "bakery", "coffee_shop", "meal_takeaway") } -> "Food"
            types.any { it in setOf("bar", "pub", "night_club") } -> "Drinks"
            types.any { it in setOf("museum", "art_gallery", "cultural_landmark", "historical_landmark", "library", "artwork") } -> "Culture"
            types.any { it in setOf("tourist_attraction", "park", "garden", "point_of_interest", "observation_deck") } -> "Viewpoint"
            else -> "Culture"
        }
    }

    fun priority(category: String): Int {
        return when (category) {
            "Food" -> 4
            "Drinks" -> 3
            "Culture" -> 2
            "Viewpoint" -> 2
            else -> 0
        }
    }
}
