package com.novahorizon.wanderly.data.plus

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.api.decodeRpc
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject

open class PlusRepository @Inject constructor() {

    @Serializable
    data class PlusEntitlementResponse(
        val is_plus: Boolean = false,
        val status: String? = null,
        val provider: String? = null,
        val product_id: String? = null,
        val entitlement: String? = null,
        val current_period_end: String? = null
    )

    open suspend fun getMyEntitlement(): Result<PlusEntitlement> = withContext(Dispatchers.IO) {
        try {
            val response = SupabaseClient.client.postgrest
                .rpc("get_my_plus_entitlement")
                .decodeRpc<PlusEntitlementResponse>()

            Result.success(mapEntitlementResponse(response))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Could not load Plus entitlement", e)
            Result.failure(e)
        }
    }

    open suspend fun isPlus(): Result<Boolean> =
        getMyEntitlement().map { entitlement -> entitlement.shouldShowActiveBadge() }

    companion object {
        fun mapEntitlementResponse(
            response: PlusEntitlementResponse?,
            now: Instant = Instant.now()
        ): PlusEntitlement {
            if (response == null) return PlusEntitlement.free()

            val currentPeriodEnd = parseInstant(response.current_period_end)
            val mapped = PlusEntitlement(
                isPlus = response.is_plus,
                status = response.status,
                provider = response.provider,
                productId = response.product_id,
                entitlement = response.entitlement,
                currentPeriodEnd = currentPeriodEnd
            )

            return mapped.copy(isPlus = mapped.shouldShowActiveBadge(now, allowDevStatus = BuildConfig.DEBUG))
        }

        internal fun parseInstant(value: String?): Instant? {
            val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { Instant.parse(trimmed) }
                .recoverCatching { OffsetDateTime.parse(trimmed).toInstant() }
                .getOrNull()
        }

        private fun logError(message: String, throwable: Throwable) {
            AppLogger.e(
                "PlusRepository",
                "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
            )
        }
    }
}
