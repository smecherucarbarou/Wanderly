package com.novahorizon.wanderly.api

import io.github.jan.supabase.postgrest.result.PostgrestResult
import kotlinx.serialization.json.Json

/**
 * Shared JSON used to decode Supabase RPC / PostgREST responses.
 *
 * RPCs return `jsonb_build_object(...)` payloads whose key set evolves on the server (e.g. a new
 * `badge` field). A strict decoder throws [kotlinx.serialization.json.internal.JsonDecodingException]
 * on any unmodeled key, which silently turns a *successful* server mutation into a client-side error
 * (the RPC has already committed by the time decoding fails). Tolerating unknown keys keeps one new
 * server column from breaking every client. `coerceInputValues` maps explicit `null`s to defaults.
 *
 * Wired as the Supabase client's `defaultSerializer`, so it covers every `decodeSingle`/`decodeList`
 * call across all repositories — not just gems.
 */
val SupabaseRpcJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Decodes the raw body of an RPC/PostgREST result with [SupabaseRpcJson]. Use this instead of
 * `decodeSingle()` for `jsonb`-returning RPCs so unknown server keys never break the decode.
 */
inline fun <reified T> PostgrestResult.decodeRpc(): T = SupabaseRpcJson.decodeFromString(data)
