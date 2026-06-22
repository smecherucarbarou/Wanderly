package com.novahorizon.wanderly.ui.profile

import com.novahorizon.wanderly.data.Profile

object ProfileBadgeEvaluator {
    fun updatedProfileWithUnlockedBadges(profile: Profile): Profile {
        val currentHoney = profile.honey ?: 0
        val currentStreak = profile.streak_count ?: 0
        val visitedCities = profile.cities_visited?.size ?: 0
        val unlockedBadges = (profile.badges?.toSet() ?: emptySet()).toMutableSet()

        unlockedBadges.add("first_flight")
        if (currentStreak >= 7) unlockedBadges.add("7-day_streak")
        if (currentHoney >= 50 || !profile.last_mission_date.isNullOrBlank()) unlockedBadges.add("photographer")
        if (visitedCities >= 3) unlockedBadges.add("cartographer")
        // gem_finder is awarded server-side by discover_gem_by_place on first discovery.
        if (currentHoney >= 1000) unlockedBadges.add("queen_bee")

        return profile.copy(badges = unlockedBadges.toList())
    }
}
