package com.novahorizon.wanderly.data

import kotlinx.serialization.Serializable

/** A row from the `shop_items` catalog table (matches live schema). */
@Serializable
data class ShopItem(
    val id: String,
    val sku: String,
    val name: String,
    val type: String,
    val cost_honey: Int,
    val active: Boolean = true
)

/** `user_inventory` row projection: only the owned item id is read client-side. */
@Serializable
data class UserInventoryItemRow(
    val item_id: String
)

/** Cosmetic `shop_items.type` values stored on `profiles.equipped_*`. */
object CosmeticType {
    const val AVATAR_FRAME = "avatar_frame"
    const val BUZZY_SKIN = "buzzy_skin"
    const val WIDGET_THEME = "widget_theme"
}

/**
 * UI-facing shop entry combining a catalog row with the viewer's honey balance, inventory and the
 * currently equipped cosmetic ids.
 */
data class ShopItemStatus(
    val id: String,
    val sku: String,
    val name: String,
    val type: String,
    val costHoney: Int,
    val owned: Boolean,
    val equipped: Boolean,
    val affordable: Boolean
)

/** Result of `purchase_shop_item`. */
sealed class ShopPurchaseResult {
    /** Server committed the purchase and echoed the new honey balance + purchased sku. */
    data class Success(val newHoney: Int, val sku: String?) : ShopPurchaseResult()
    object ItemUnavailable : ShopPurchaseResult()
    object AlreadyOwned : ShopPurchaseResult()
    object InsufficientHoney : ShopPurchaseResult()
    object Unauthenticated : ShopPurchaseResult()
    object Failure : ShopPurchaseResult()
}

/** Result of `equip_cosmetic`. */
sealed class ShopEquipResult {
    /** Server equipped the cosmetic and echoed its type (avatar_frame|buzzy_skin|widget_theme). */
    data class Success(val type: String?) : ShopEquipResult()
    object NotOwned : ShopEquipResult()
    object Unauthenticated : ShopEquipResult()
    object Failure : ShopEquipResult()
}
