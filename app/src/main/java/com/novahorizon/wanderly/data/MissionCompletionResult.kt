package com.novahorizon.wanderly.data

sealed class MissionCompletionResult {
    data class Completed(
        val honey: Int,
        val streakCount: Int,
        val lastMissionDate: String,
        val rewardHoney: Int,
        val streakBonusHoney: Int
    ) : MissionCompletionResult()

    data class AlreadyCompleted(
        val honey: Int,
        val streakCount: Int,
        val lastMissionDate: String?
    ) : MissionCompletionResult()

    object Unauthenticated : MissionCompletionResult()
    object Forbidden : MissionCompletionResult()
    object RateLimited : MissionCompletionResult()
    object NetworkFailure : MissionCompletionResult()
    object ServerFailure : MissionCompletionResult()
    object ParseFailure : MissionCompletionResult()
}

sealed class SensitiveProfileMutationResult {
    data class Success(
        val profile: Profile? = null,
        val reason: String? = null
    ) : SensitiveProfileMutationResult()

    data class Rejected(
        val reason: String
    ) : SensitiveProfileMutationResult()

    object Unauthenticated : SensitiveProfileMutationResult()
    object Forbidden : SensitiveProfileMutationResult()
    object RateLimited : SensitiveProfileMutationResult()
    object NetworkFailure : SensitiveProfileMutationResult()
    object ServerFailure : SensitiveProfileMutationResult()
    object ParseFailure : SensitiveProfileMutationResult()
}
