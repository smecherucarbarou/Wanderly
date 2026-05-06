import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.4"

const MAX_FIELD_MASK_LENGTH = 256
const MAX_BODY_BYTES = 16 * 1024
const MAX_RESULT_COUNT = 20
const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? ""
const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? ""

interface PlacesErrorResponse {
  ok: false
  error: {
    type: string
    status: number
    message: string
    upstream_body?: string
  }
}

type AuthContext = {
  userId: string
  token: string
}

function allowedOrigins(): string[] {
  return (Deno.env.get("ALLOWED_ORIGINS") ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0)
}

function buildCorsHeaders(req: Request): Headers {
  const headers = new Headers({
    "Content-Type": "application/json",
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    Vary: "Origin",
  })
  const origin = req.headers.get("origin")
  if (origin != null && allowedOrigins().includes(origin)) {
    headers.set("Access-Control-Allow-Origin", origin)
  }
  return headers
}

function jsonResponse(req: Request, body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: buildCorsHeaders(req),
  })
}

function diagnosticId(): string {
  return crypto.randomUUID()
}

function sanitizedUpstreamBody(responseText: string): string {
  return responseText.length > 0 ? "[redacted]" : ""
}

function describeError(error: unknown): string {
  return error instanceof Error ? error.name : typeof error
}

function placesError(
  req: Request,
  type: string,
  status: number,
  message: string,
  upstreamBody?: string,
): Response {
  const body: PlacesErrorResponse = {
    ok: false,
    error: { type, status, message, upstream_body: upstreamBody },
  }
  return jsonResponse(req, body as unknown as Record<string, unknown>, status)
}

function sanitizePlacesResponse(responseText: string): string {
  try {
    const parsed = JSON.parse(responseText)
    if (!isRecord(parsed)) return responseText
    const sanitized: Record<string, unknown> = {}
    if (Array.isArray(parsed.places)) sanitized.places = parsed.places
    return JSON.stringify(sanitized)
  } catch {
    return responseText
  }
}

const UPSTREAM_TIMEOUT_MS = 30_000

async function fetchWithTimeout(
  input: string | URL | Request,
  init: RequestInit = {},
  timeoutMs = UPSTREAM_TIMEOUT_MS,
): Promise<Response> {
  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await fetch(input, { ...init, signal: controller.signal })
  } finally {
    clearTimeout(timeoutId)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function createSupabaseForToken(token: string) {
  if (!supabaseUrl || !supabaseAnonKey) {
    throw new Error("Missing Supabase auth config")
  }

  return createClient(supabaseUrl, supabaseAnonKey, {
    auth: {
      autoRefreshToken: false,
      persistSession: false,
    },
    global: {
      headers: { Authorization: `Bearer ${token}` },
    },
  })
}

async function verifyAuth(req: Request): Promise<AuthContext | null> {
  const authorization = req.headers.get("authorization") ?? ""
  if (!authorization.startsWith("Bearer ")) return null

  const token = authorization.replace(/^Bearer\s+/i, "")
  const supabase = createSupabaseForToken(token)

  const { data, error } = await supabase.auth.getUser(token)
  if (error || !data.user) return null
  return { userId: data.user.id, token }
}

function maxRequestsPerDay(envName: string, fallback: number): number {
  const parsed = Number.parseInt(Deno.env.get(envName) ?? "", 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

async function consumeApiQuota(
  req: Request,
  auth: AuthContext,
  provider: "gemini" | "places",
  maxRequests: number,
): Promise<boolean> {
  if (!auth.userId) return false
  const supabase = createSupabaseForToken(auth.token)
  const { data, error } = await supabase.rpc("consume_api_quota", {
    provider_name: provider,
    max_requests_per_day: maxRequests,
  })
  if (error) {
    throw new Error("Quota check failed")
  }
  return data === true
}

Deno.serve(async (req: Request) => {
  const corsHeaders = buildCorsHeaders(req)
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  if (req.method !== "POST") {
    return placesError(req, "method_not_allowed", 405, "Method not allowed")
  }

  const authorization = req.headers.get("authorization") ?? ""
  if (!authorization.startsWith("Bearer ")) {
    return placesError(req, "missing_bearer_token", 401, "Missing bearer token")
  }

  let auth: AuthContext | null
  try {
    auth = await verifyAuth(req)
  } catch {
    return placesError(req, "missing_supabase_auth_config", 500, "Server configuration unavailable")
  }
  if (!auth) {
    return placesError(req, "invalid_bearer_token", 401, "Invalid bearer token")
  }

  const placesApiKey = Deno.env.get("MAPS_API_KEY")
  if (!placesApiKey) {
    return placesError(req, "missing_maps_api_key", 500, "Server configuration unavailable")
  }

  try {
    const requestBody = await req.text()
    if (requestBody.length === 0 || requestBody.length > MAX_BODY_BYTES) {
      return placesError(req, "invalid_request_size", 413, "Invalid request size")
    }

    let payload: unknown
    try {
      payload = JSON.parse(requestBody)
    } catch {
      return placesError(req, "malformed_json_body", 400, "Malformed JSON body")
    }

    const fieldMask = isRecord(payload) && typeof payload.fieldMask === "string" ? payload.fieldMask : ""
    const body = isRecord(payload) ? payload.body : null

    if (
      !fieldMask ||
      !isRecord(body) ||
      fieldMask.length > MAX_FIELD_MASK_LENGTH ||
      !/^[A-Za-z0-9_.,]+$/.test(fieldMask) ||
      typeof body.textQuery !== "string" ||
      body.textQuery.trim().length === 0 ||
      body.textQuery.length > 200
    ) {
      return placesError(req, "invalid_places_request", 400, "fieldMask and body.textQuery are required")
    }

    const quotaAllowed = await consumeApiQuota(req, auth, "places", maxRequestsPerDay("PLACES_DAILY_QUOTA", 100))
    if (!quotaAllowed) {
      return placesError(req, "quota_exhausted", 429, "Daily Google Places limit reached. Try again tomorrow.")
    }

    const sanitizedBody: Record<string, unknown> = {
      textQuery: body.textQuery,
    }
    if (isRecord(body.locationBias)) sanitizedBody.locationBias = body.locationBias
    if (isRecord(body.locationRestriction)) sanitizedBody.locationRestriction = body.locationRestriction
    if (typeof body.includedType === "string") sanitizedBody.includedType = body.includedType
    const clientMaxResults = typeof body.maxResultCount === "number" ? body.maxResultCount : 0
    if (clientMaxResults > 0) {
      sanitizedBody.maxResultCount = Math.min(clientMaxResults, MAX_RESULT_COUNT)
    }

    const locationBias = sanitizedBody.locationBias
    const circle = isRecord(locationBias) ? (locationBias as Record<string, unknown>).circle : null
    const radius = isRecord(circle)
      ? (circle as Record<string, unknown>).radius
      : null
    console.log(
      `Places text search request query_length=${(body.textQuery as string).trim().length} field_mask_length=${fieldMask.length} radius=${typeof radius === "number" ? radius : "none"}`,
    )

    const response = await fetchWithTimeout("https://places.googleapis.com/v1/places:searchText", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": placesApiKey,
        "X-Goog-FieldMask": fieldMask,
      },
      body: JSON.stringify(sanitizedBody),
    })

    const responseText = await response.text()
    if (!response.ok) {
      const errorId = diagnosticId()
      console.error(`Places upstream error id=${errorId} status=${response.status} body_bytes=${responseText.length}`)
      return placesError(
        req,
        "places_upstream_request_failed",
        502,
        "Places upstream request failed",
        sanitizedUpstreamBody(responseText),
      )
    }

    const sanitizedResponse = sanitizePlacesResponse(responseText)
    try {
      const parsed = JSON.parse(sanitizedResponse)
      const count = Array.isArray(parsed.places) ? parsed.places.length : 0
      console.log(`Places text search result count=${count}`)
    } catch {
      console.log("Places text search result count=unknown")
    }

    return new Response(sanitizedResponse, {
      status: 200,
      headers: corsHeaders,
    })
  } catch (error) {
    const errorId = diagnosticId()
    console.error(`Places proxy internal error id=${errorId} kind=${describeError(error)}`)
    return placesError(req, "places_proxy_internal_error", 502, "Places proxy request failed")
  }
})
