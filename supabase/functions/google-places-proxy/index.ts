import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const MAX_FIELD_MASK_LENGTH = 256
const MAX_BODY_BYTES = 16 * 1024
const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? ""
const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? ""

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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

async function verifyAuth(req: Request): Promise<string | null> {
  const authorization = req.headers.get("authorization") ?? ""
  if (!authorization.startsWith("Bearer ")) return null
  if (!supabaseUrl || !supabaseAnonKey) {
    throw new Error("Missing Supabase auth config")
  }

  const token = authorization.replace(/^Bearer\s+/i, "")
  const supabase = createClient(supabaseUrl, supabaseAnonKey, {
    auth: {
      autoRefreshToken: false,
      persistSession: false,
    },
    global: {
      headers: { Authorization: `Bearer ${token}` },
    },
  })

  const { data, error } = await supabase.auth.getUser(token)
  if (error || !data.user) return null
  return data.user.id
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

  let userId: string | null
  try {
    userId = await verifyAuth(req)
  } catch {
    return jsonResponse(req, { error: "Missing Supabase auth config" }, 500)
  }
  if (!userId) {
    return jsonResponse(req, { error: "Invalid bearer token" }, 401)
  }

  const placesApiKey = Deno.env.get("MAPS_API_KEY")
  if (!placesApiKey) {
    return jsonResponse(req, { error: "Missing MAPS_API_KEY secret" }, 500)
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
      return jsonResponse(req, { error: "Places upstream request failed" }, response.status)
    }

    return new Response(responseText, {
      status: response.status,
      headers: corsHeaders,
    })
  } catch {
    return jsonResponse(req, { error: "Places proxy request failed" }, 500)
  }
})
