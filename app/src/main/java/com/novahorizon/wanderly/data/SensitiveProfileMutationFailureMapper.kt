package com.novahorizon.wanderly.data

import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * Maps a thrown exception to a typed [SensitiveProfileMutationResult]. Shared by the streak/referral
 * mutation flows carved out of ProfileRepository (big_improvements A) so error semantics stay uniform.
 */
internal fun mapSensitiveProfileMutationFailure(error: Exception): SensitiveProfileMutationResult =
    when (error) {
        is SerializationException -> SensitiveProfileMutationResult.ParseFailure
        is HttpRequestTimeoutException,
        is IOException -> SensitiveProfileMutationResult.NetworkFailure
        is RestException -> when (error.statusCode) {
            401 -> SensitiveProfileMutationResult.Unauthenticated
            403 -> SensitiveProfileMutationResult.Forbidden
            429 -> SensitiveProfileMutationResult.RateLimited
            in 500..599 -> SensitiveProfileMutationResult.ServerFailure
            else -> SensitiveProfileMutationResult.ServerFailure
        }
        else -> SensitiveProfileMutationResult.ServerFailure
    }
