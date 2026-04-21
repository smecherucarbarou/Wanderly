package com.novahorizon.wanderly.ui.profile

import com.novahorizon.wanderly.data.Profile

object ProfileBadgeEvaluator {
    fun updatedProfileWithUnlockedBadges(profile: Profile): Profile {
        val currentHoney = profile.honey ?: 0
        val currentStreak = profile.streak_count ?: 0
        val unlockedBadges = (profile.badges?.toSet() ?: emptySet()).toMutableSet()

        unlockedBadges.add("Early Bee")
        if (currentHoney >= 100) unlockedBadges.add("Scout Bee")
        if (currentHoney >= 300) unlockedBadges.add("Expert Bee")
        if (currentHoney >= 600) unlockedBadges.add("Queen Explorer")
        if (currentHoney >= 1000) unlockedBadges.add("Honey Hoarder")
        if (currentStreak >= 7) unlockedBadges.add("Streak Master")

        return profile.copy(badges = unlockedBadges.toList())
    }
}
