package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.Constants
import com.novahorizon.wanderly.api.SupabaseClient
import com.novahorizon.wanderly.api.decodeRpc
import com.novahorizon.wanderly.auth.AuthSessionCoordinator
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Shop catalog + cosmetic purchase/equip. Carved out of ProfileRepository (big_improvements A, 3c);
 * shares the [ProfileStateHolder] so honey balance and equipped cosmetics remain a single source of
 * truth. Public result/DTO types live in Shop.kt; the RPC params/responses are private to this repo.
 */
class ShopRepository(
    private val profileState: ProfileStateHolder
) {
    @Serializable
    private data class ShopItemParams(val p_item_id: String)

    @Serializable
    internal data class PurchaseShopItemRpcResponse(
        val success: Boolean,
        val error: String? = null,
        val honey: Int? = null,
        val item: String? = null
    )

    @Serializable
    internal data class EquipCosmeticRpcResponse(
        val success: Boolean,
        val error: String? = null,
        val type: String? = null
    )

    suspend fun getShopItems(): List<ShopItemStatus> = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext emptyList()
        val userId = session.user?.id ?: return@withContext emptyList()

        try {
            val catalog = withPostgrestSchemaCacheRetry {
                SupabaseClient.client.postgrest[Constants.TABLE_SHOP_ITEMS]
                    .select { filter { eq("active", true) } }
                    .decodeList<ShopItem>()
            }.sortedWith(compareBy({ it.type }, { it.cost_honey }))

            val ownedIds = withPostgrestSchemaCacheRetry {
                SupabaseClient.client.postgrest[Constants.TABLE_USER_INVENTORY]
                    .select(Columns.list("item_id")) { filter { eq("user_id", userId) } }
                    .decodeList<UserInventoryItemRow>()
            }.map { it.item_id }.toSet()

            val profile = profileState.value
            val honey = profile?.honey ?: 0
            val equippedIds = setOfNotNull(
                profile?.equipped_frame,
                profile?.equipped_skin,
                profile?.equipped_widget_theme
            )

            catalog.map { item ->
                ShopItemStatus(
                    id = item.id,
                    sku = item.sku,
                    name = item.name,
                    type = item.type,
                    costHoney = item.cost_honey,
                    owned = item.id in ownedIds,
                    equipped = item.id in equippedIds,
                    affordable = honey >= item.cost_honey
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Failed to load shop items: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun purchaseShopItem(itemId: String): ShopPurchaseResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext ShopPurchaseResult.Unauthenticated
        session.user?.id ?: return@withContext ShopPurchaseResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("purchase_shop_item", ShopItemParams(itemId))
                .decodeRpc<PurchaseShopItemRpcResponse>()
            val result = mapPurchaseResponse(response)
            if (result is ShopPurchaseResult.Success) {
                // RPC echoes the post-debit balance; refresh honey in the shared profile state.
                profileState.update { it?.copy(honey = result.newHoney) }
            }
            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Shop purchase failed: ${e.message}", e)
            ShopPurchaseResult.Failure
        }
    }

    suspend fun equipCosmetic(itemId: String): ShopEquipResult = withContext(Dispatchers.IO) {
        val session = AuthSessionCoordinator.awaitResolvedSessionOrNull()
            ?: return@withContext ShopEquipResult.Unauthenticated
        session.user?.id ?: return@withContext ShopEquipResult.Unauthenticated

        try {
            val response = SupabaseClient.client.postgrest
                .rpc("equip_cosmetic", ShopItemParams(itemId))
                .decodeRpc<EquipCosmeticRpcResponse>()
            val result = mapEquipResponse(response)
            if (result is ShopEquipResult.Success) {
                applyEquippedCosmetic(result.type, itemId)
            }
            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Shop equip failed: ${e.message}", e)
            ShopEquipResult.Failure
        }
    }

    private fun applyEquippedCosmetic(type: String?, itemId: String) {
        profileState.update { current ->
            when (type) {
                CosmeticType.AVATAR_FRAME -> current?.copy(equipped_frame = itemId)
                CosmeticType.BUZZY_SKIN -> current?.copy(equipped_skin = itemId)
                CosmeticType.WIDGET_THEME -> current?.copy(equipped_widget_theme = itemId)
                else -> current
            }
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            val safeMessage = LogRedactor.redact(message)
            if (throwable != null) {
                AppLogger.e(
                    "ShopRepository",
                    "$safeMessage [${throwable.javaClass.simpleName}: ${LogRedactor.redact(throwable.message)}]"
                )
            } else {
                AppLogger.e("ShopRepository", safeMessage)
            }
        }
    }

    companion object {
        internal fun mapPurchaseResponse(response: PurchaseShopItemRpcResponse): ShopPurchaseResult =
            if (response.success) {
                ShopPurchaseResult.Success(newHoney = response.honey ?: 0, sku = response.item)
            } else when (response.error) {
                "item_unavailable" -> ShopPurchaseResult.ItemUnavailable
                "already_owned" -> ShopPurchaseResult.AlreadyOwned
                "insufficient_honey" -> ShopPurchaseResult.InsufficientHoney
                "not_authenticated" -> ShopPurchaseResult.Unauthenticated
                else -> ShopPurchaseResult.Failure
            }

        internal fun mapEquipResponse(response: EquipCosmeticRpcResponse): ShopEquipResult =
            if (response.success) {
                ShopEquipResult.Success(type = response.type)
            } else when (response.error) {
                "not_owned" -> ShopEquipResult.NotOwned
                "not_authenticated" -> ShopEquipResult.Unauthenticated
                else -> ShopEquipResult.Failure
            }
    }
}
