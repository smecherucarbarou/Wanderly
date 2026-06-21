package com.novahorizon.wanderly.data.plus

import java.time.Instant
import java.util.Locale

data class PlusEntitlement(
    val isPlus: Boolean,
    val status: String?,
    val provider: String?,
    val productId: String?,
    val entitlement: String?,
    val currentPeriodEnd: Instant?
) {
    fun shouldShowActiveBadge(
        now: Instant = Instant.now(),
        allowDevStatus: Boolean = true
    ): Boolean {
        if (!isPlus) return false
        val normalizedStatus = status?.trim()?.lowercase(Locale.ROOT)
        if (normalizedStatus in inactiveStatuses) return false
        if (normalizedStatus == "dev" && !allowDevStatus) return false
        if (currentPeriodEnd != null && !currentPeriodEnd.isAfter(now)) return false
        return true
    }

    companion object {
        private val inactiveStatuses = setOf("expired", "revoked", "past_due")

        fun free(): PlusEntitlement =
            PlusEntitlement(
                isPlus = false,
                status = null,
                provider = null,
                productId = null,
                entitlement = null,
                currentPeriodEnd = null
            )
    }
}
