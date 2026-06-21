package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * Persists AI/weekly generated missions in the `missions` table so each mission has a stable id.
 * Completion is then logged per-mission via `log_mission_completion` (see [ProfileRepository]).
 */
open class MissionRepository {

    @Serializable
    private data class MissionInsert(
        val user_id: String,
        val title: String,
        val description: String? = null,
        val category: String? = null,
        val place_id: String? = null,
        val place_name: String? = null,
        val lat: Double? = null,
        val lng: Double? = null,
        val source: String? = null,
        val expires_at: String? = null
        // reward_honey intentionally omitted: the server clamps it at completion.
    )

    @Serializable
    private data class MissionIdRow(val id: String)

    /**
     * Inserts a mission owned by the current user and returns its id, or null on failure.
     * Insertion failures are non-fatal: the mission is still shown locally, completion will
     * simply report a missing mission if it was never persisted.
     */
    open suspend fun insertMission(
        title: String,
        description: String?,
        category: String?,
        placeId: String?,
        placeName: String?,
        lat: Double?,
        lng: Double?,
        source: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext null
            val userId = session.user?.id ?: return@withContext null

            val payload = MissionInsert(
                user_id = userId,
                title = title,
                description = description,
                category = category,
                place_id = placeId,
                place_name = placeName,
                lat = lat,
                lng = lng,
                source = source,
                expires_at = Instant.now().plus(Duration.ofHours(MISSION_TTL_HOURS)).toString()
            )

            SupabaseClient.client.postgrest["missions"]
                .insert(payload) { select(Columns.list("id")) }
                .decodeSingle<MissionIdRow>()
                .id
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Mission insert failed", e)
            null
        }
    }

    private fun logError(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            AppLogger.e(
                "MissionRepository",
                "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
            )
        } else {
            AppLogger.e("MissionRepository", message)
        }
    }

    private companion object {
        private const val MISSION_TTL_HOURS = 24L
    }
}
