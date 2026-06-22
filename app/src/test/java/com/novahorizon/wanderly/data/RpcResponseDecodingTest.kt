package com.novahorizon.wanderly.data

import com.novahorizon.wanderly.api.SupabaseRpcJson
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression tests for RPC response decoding. Each payload is the real shape returned by the live
 * `jsonb_build_object(...)` of the corresponding function. These guard against the class of bug where
 * the server returns a key the client DTO doesn't model and a strict decoder turns a committed
 * server mutation into a client-side failure.
 */
class RpcResponseDecodingTest {

    private val strictJson = Json.Default

    @Test
    fun `strict decoder breaks when server returns an unmodeled key`() {
        // This is exactly the failure that broke gem discovery in production.
        val payload = """{"success":true,"reward_honey":10,"gem_id":"d1","future_field":1}"""
        try {
            strictJson.decodeFromString<DiscoverGemRpcResponse>(payload)
            fail("Expected strict decoder to reject the unknown key")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `shared rpc json tolerates unknown server keys`() {
        val payload = """{"success":true,"reward_honey":10,"gem_id":"d1","badge":"gem_finder","future_field":1}"""
        val response = SupabaseRpcJson.decodeFromString<DiscoverGemRpcResponse>(payload)
        assertEquals(GemDiscoveryResult.Success(10), mapDiscoverGemResponse(response))
    }

    @Test
    fun `discover_gem_by_place first-discovery payload decodes to Success`() {
        val payload = """{"success":true,"reward_honey":10,"gem_id":"d1","badge":"gem_finder"}"""
        val response = SupabaseRpcJson.decodeFromString<DiscoverGemRpcResponse>(payload)
        assertTrue(response.success)
        assertEquals("gem_finder", response.badge)
        assertEquals(GemDiscoveryResult.Success(10), mapDiscoverGemResponse(response))
    }

    @Test
    fun `discover_gem_by_place repeat-discovery payload decodes to Success with null badge`() {
        val payload = """{"success":true,"reward_honey":10,"gem_id":"d1","badge":null}"""
        val response = SupabaseRpcJson.decodeFromString<DiscoverGemRpcResponse>(payload)
        assertEquals(GemDiscoveryResult.Success(10), mapDiscoverGemResponse(response))
    }

    @Test
    fun `discover_gem_by_place already_discovered payload maps correctly`() {
        val payload = """{"success":false,"error":"already_discovered","gem_id":"d1"}"""
        val response = SupabaseRpcJson.decodeFromString<DiscoverGemRpcResponse>(payload)
        assertEquals(GemDiscoveryResult.AlreadyDiscovered, mapDiscoverGemResponse(response))
    }

    @Test
    fun `use_streak_freeze success payload decodes`() {
        val payload = """{"success":true,"freezes_left":2}"""
        val response = SupabaseRpcJson.decodeFromString<ProfileRepository.StreakFreezeRpcResponse>(payload)
        assertTrue(response.success)
        assertEquals(2, response.freezes_left)
    }

    @Test
    fun `claim_streak_milestone success payload with badge decodes`() {
        val payload = """{"success":true,"reward_honey":50,"badge":"week_warrior"}"""
        val response = SupabaseRpcJson.decodeFromString<ProfileRepository.StreakMilestoneClaimRpcResponse>(payload)
        assertTrue(response.success)
        assertEquals(50, response.reward_honey)
        assertEquals("week_warrior", response.badge)
    }

    @Test
    fun `claim_referral success payload decodes`() {
        val payload = """{"success":true,"reward_honey":100}"""
        val response = SupabaseRpcJson.decodeFromString<ProfileRepository.ReferralClaimRpcResponse>(payload)
        assertTrue(response.success)
        assertEquals(100, response.reward_honey)
    }

    @Test
    fun `purchase_shop_item success payload decodes all keys and maps to Success`() {
        // Real shape: jsonb_build_object('success',true,'honey',v_honey,'item',v_item.sku)
        val payload = """{"success":true,"honey":120,"item":"frame_hex"}"""
        val response = SupabaseRpcJson.decodeFromString<ProfileRepository.PurchaseShopItemRpcResponse>(payload)
        assertTrue(response.success)
        assertEquals(120, response.honey)
        assertEquals("frame_hex", response.item)
        assertEquals(
            ShopPurchaseResult.Success(newHoney = 120, sku = "frame_hex"),
            ProfileRepository.mapPurchaseResponse(response)
        )
    }

    @Test
    fun `purchase_shop_item insufficient_honey payload maps correctly`() {
        val payload = """{"success":false,"error":"insufficient_honey"}"""
        val response = SupabaseRpcJson.decodeFromString<ProfileRepository.PurchaseShopItemRpcResponse>(payload)
        assertEquals(ShopPurchaseResult.InsufficientHoney, ProfileRepository.mapPurchaseResponse(response))
    }

    @Test
    fun `purchase_shop_item already_owned payload maps correctly`() {
        val payload = """{"success":false,"error":"already_owned"}"""
        val response = SupabaseRpcJson.decodeFromString<ProfileRepository.PurchaseShopItemRpcResponse>(payload)
        assertEquals(ShopPurchaseResult.AlreadyOwned, ProfileRepository.mapPurchaseResponse(response))
    }

    @Test
    fun `equip_cosmetic success payload decodes all keys and maps to Success`() {
        // Real shape: jsonb_build_object('success',true,'type',v_type)
        val payload = """{"success":true,"type":"avatar_frame"}"""
        val response = SupabaseRpcJson.decodeFromString<ProfileRepository.EquipCosmeticRpcResponse>(payload)
        assertTrue(response.success)
        assertEquals("avatar_frame", response.type)
        assertEquals(
            ShopEquipResult.Success(type = "avatar_frame"),
            ProfileRepository.mapEquipResponse(response)
        )
    }

    @Test
    fun `equip_cosmetic not_owned payload maps correctly`() {
        val payload = """{"success":false,"error":"not_owned"}"""
        val response = SupabaseRpcJson.decodeFromString<ProfileRepository.EquipCosmeticRpcResponse>(payload)
        assertEquals(ShopEquipResult.NotOwned, ProfileRepository.mapEquipResponse(response))
    }
}
