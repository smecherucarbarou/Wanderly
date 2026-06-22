package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.util.GeoMath
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Discovers gems via the live `discover_gem_by_place` RPC (find-or-create + reward),
 * gated client-side by physical proximity. Counts the user's own discoveries for the
 * Profile "Gems" stat (RLS `gd_select_own` restricts the read to the caller).
 */
open class GemDiscoveryRepository {

    @Serializable
    private data class GemDiscoveryRow(val gem_id: String)

    open suspend fun discoverGem(
        gem: Gem,
        currentLat: Double,
        currentLng: Double
    ): GemDiscoveryResult = withContext(Dispatchers.IO) {
        val distanceKm = GeoMath.distanceKm(currentLat, currentLng, gem.lat, gem.lng)
        if (!GemProximity.isWithinRange(distanceKm)) {
            return@withContext GemDiscoveryResult.TooFar
        }

        try {
            val response = SupabaseClient.client.postgrest
                .rpc(
                    "discover_gem_by_place",
                    DiscoverGemParams(
                        p_name = gem.name,
                        p_lat = gem.lat,
                        p_lng = gem.lng,
                        p_category = gem.category
                    )
                )
                .decodeSingle<DiscoverGemRpcResponse>()
            mapDiscoverGemResponse(response)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Gem discovery failed", e)
            GemDiscoveryResult.Error
        }
    }

    open suspend fun countMyDiscoveries(): Int = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull() ?: return@withContext 0
        val userId = session.user?.id ?: return@withContext 0
        try {
            SupabaseClient.client.postgrest["gem_discoveries"]
                .select(Columns.list("gem_id")) { filter { eq("user_id", userId) } }
                .decodeList<GemDiscoveryRow>()
                .size
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Gem discovery count failed", e)
            0
        }
    }

    private fun logError(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            AppLogger.e(
                "GemDiscoveryRepository",
                "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}]"
            )
        } else {
            AppLogger.e("GemDiscoveryRepository", message)
        }
    }
}
