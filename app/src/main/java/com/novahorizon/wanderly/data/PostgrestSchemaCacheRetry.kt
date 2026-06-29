package com.novahorizon.wanderly.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val POSTGREST_SCHEMA_CACHE_RETRY_DELAY_MS = 1_500L

/**
 * True if [error] (or any cause in its chain) is a PostgREST schema-cache miss (PGRST002).
 * Shared by the repositories carved out of ProfileRepository (big_improvements A).
 */
internal fun isPostgrestSchemaCacheError(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { throwable ->
        throwable.message?.lowercase().orEmpty().contains("pgrst002")
    }

/** Runs [block]; on a one-off PostgREST schema-cache miss (PGRST002), waits briefly and retries once. */
internal suspend fun <T> withPostgrestSchemaCacheRetry(block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (!isPostgrestSchemaCacheError(e)) throw e
        delay(POSTGREST_SCHEMA_CACHE_RETRY_DELAY_MS)
        block()
    }
