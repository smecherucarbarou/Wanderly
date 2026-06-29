package com.novahorizon.wanderly.data

/**
 * Applies a reward/streak progress snapshot to the shared [ProfileStateHolder] atomically, then
 * caches the streak state. Shared by ProfileRepository (mission completion) and the carved-out
 * StreakRepository (accept/restore) so the merge logic lives in exactly one place (big_improvements A).
 *
 * [ensureProfileLoaded] populates the holder when it is empty (the suspend fetch cannot run inside
 * the atomic update block); it is supplied by the owner so this class stays free of network/DI deps.
 */
class ProfileProgressWriter(
    private val profileState: ProfileStateHolder,
    private val preferencesStore: PreferencesStore,
    private val ensureProfileLoaded: suspend () -> Unit
) {
    suspend fun apply(honey: Int?, streakCount: Int?, lastMissionDate: String?): Profile? {
        if (profileState.value == null) {
            ensureProfileLoaded()
        }
        val updated = profileState.updateAndGet { current ->
            current?.copy(
                honey = honey ?: current.honey,
                streak_count = streakCount ?: current.streak_count,
                last_mission_date = lastMissionDate ?: current.last_mission_date,
                hive_rank = HiveRank.fromHoney(honey ?: current.honey)
            )
        }
        if (updated != null) {
            preferencesStore.cacheProfileStreakState(
                lastMissionDate = updated.last_mission_date,
                streakCount = updated.streak_count
            )
        }
        return updated
    }
}
