import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const MAX_FIELD_MASK_LENGTH = 256
const MAX_BODY_BYTES = 16 * 1024
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
    return jsonResponse(req, { error: "Method not allowed" }, 405)
  }

  const authorization = req.headers.get("authorization") ?? ""
  if (!authorization.startsWith("Bearer ")) {
    return jsonResponse(req, { error: "Missing bearer token" }, 401)
  }

  let auth: AuthContext | null
  try {
    auth = await verifyAuth(req)
  } catch {
    return jsonResponse(req, { error: "Server configuration unavailable" }, 500)
  }
  if (!auth) {
    return jsonResponse(req, { error: "Invalid bearer token" }, 401)
  }

  const placesApiKey = Deno.env.get("MAPS_API_KEY")
  if (!placesApiKey) {
    return jsonResponse(req, { error: "Server configuration unavailable" }, 500)
  }

  try {
    const requestBody = await req.text()
    if (requestBody.length === 0 || requestBody.length > MAX_BODY_BYTES) {
      return jsonResponse(req, { error: "Invalid request size" }, 413)
    }

    let payload: unknown
    try {
      payload = JSON.parse(requestBody)
    } catch {
      return jsonResponse(req, { error: "Malformed JSON body" }, 400)
    }

    const fieldMask = isRecord(payload) && typeof payload.fieldMask === "string" ? payload.fieldMask : ""
    const body = isRecord(payload) ? payload.body : null

    if (
      !fieldMask ||
      !isRecord(body) ||
      fieldMask.length > MAX_FIELD_MASK_LENGTH ||
      !/^[A-Za-z0-9_.,*]+$/.test(fieldMask) ||
      typeof body.textQuery !== "string" ||
      body.textQuery.trim().length === 0 ||
      body.textQuery.length > 200
    ) {
      return jsonResponse(req, { error: "fieldMask and body.textQuery are required" }, 400)
    }

    const quotaAllowed = await consumeApiQuota(req, auth, "places", maxRequestsPerDay("PLACES_DAILY_QUOTA", 100))
    if (!quotaAllowed) {
      return jsonResponse(req, { error: "Quota exhausted" }, 429)
    }

    const locationBias = body.locationBias
    const circle = isRecord(locationBias) ? locationBias.circle : null
    const radius = isRecord(circle)
      ? circle.radius
      : null
    console.log(
      `Places text search request query_length=${body.textQuery.trim().length} field_mask_length=${fieldMask.length} radius=${typeof radius === "number" ? radius : "none"}`,
    )

    const response = await fetch("https://places.googleapis.com/v1/places:searchText", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": placesApiKey,
        "X-Goog-FieldMask": fieldMask,
      },
      body: JSON.stringify(body),
    })

    const responseText = await response.text()
    if (!response.ok) {
      const errorId = diagnosticId()
      console.error(`Places upstream error id=${errorId} status=${response.status} body_bytes=${responseText.length}`)
      return placesError(
        req,
        "places_upstream_request_failed",
        response.status,
        "Places upstream request failed",
        sanitizedUpstreamBody(responseText),
      )
    }

    try {
      const parsed = JSON.parse(responseText)
      const count = Array.isArray(parsed.places) ? parsed.places.length : 0
      console.log(`Places text search result count=${count}`)
    } catch {
      console.log("Places text search result count=unknown")
    }

    return new Response(responseText, {
      status: response.status,
      headers: corsHeaders,
    })
  } catch (error) {
    const errorId = diagnosticId()
    console.error(`Places proxy internal error id=${errorId} kind=${describeError(error)}`)
    return jsonResponse(req, { error: "Places proxy request failed", detail: "Internal error" }, 502)
  }
})
