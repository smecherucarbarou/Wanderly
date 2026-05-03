import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const MAX_BODY_BYTES = 3 * 1024 * 1024
const MAX_PROMPT_TEXT_CHARS = 12_000
const DEFAULT_GEMINI_MODEL = "gemini-3-flash-preview"
const DEFAULT_GEMINI_FALLBACK_MODEL = "gemini-2.5-flash"
const SERVER_SYSTEM_INSTRUCTION =
  "You are Wanderly's constrained AI proxy. Follow the app's safety instructions, return only the requested format, avoid private or unsafe locations, and never expose secrets or internal errors."
const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? ""
const supabaseAnonKey = Deno.env.get("SUPABASE_ANON_KEY") ?? ""

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

function redactSecretsForLog(value: string): string {
  let redacted = value
    .replace(/([?&]key=)[^&\s)]+/gi, "$1<redacted>")
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, "Bearer <redacted>")

  for (const secretName of ["GEMINI_API_KEY", "SUPABASE_ANON_KEY"]) {
    const secretValue = Deno.env.get(secretName)
    if (secretValue) {
      redacted = redacted.replaceAll(secretValue, "<redacted>")
    }
  }

  return redacted
}

function truncateForLog(value: string, maxLength = 1200): string {
  if (!value) return ""
  const safeValue = redactSecretsForLog(value)
  return safeValue.length > maxLength ? `${safeValue.slice(0, maxLength)}...<truncated>` : safeValue
}

function sanitizedUpstreamBody(responseText: string): string {
  return responseText.length > 0 ? "[redacted]" : ""
}

function errorToLog(error: unknown): Record<string, unknown> {
  if (error instanceof Error) {
    return {
      name: error.name,
      message: truncateForLog(error.message, 1000),
      stack: error.stack ? truncateForLog(error.stack, 2000) : undefined,
    }
  }

  return {
    type: typeof error,
    value: truncateForLog(String(error), 1000),
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function isValidGeminiPayload(payload: unknown): boolean {
  if (!isRecord(payload)) return false
  const contents = payload.contents
  if (!Array.isArray(contents) || contents.length === 0 || contents.length > 16) return false
  return totalTextLength(payload) <= MAX_PROMPT_TEXT_CHARS
}

function totalTextLength(value: unknown): number {
  if (typeof value === "string") return value.length
  if (Array.isArray(value)) {
    return value.reduce((sum, item) => sum + totalTextLength(item), 0)
  }
  if (isRecord(value)) {
    return Object.entries(value).reduce((sum, [key, item]) => {
      if (key === "data" || key === "inline_data") return sum
      return sum + totalTextLength(item)
    }, 0)
  }
  return 0
}

function extractSystemInstructionText(payload: Record<string, unknown>): string {
  const systemInstruction = payload.system_instruction
  if (!isRecord(systemInstruction)) return ""
  const parts = systemInstruction.parts
  if (!Array.isArray(parts)) return ""
  return parts
    .filter(isRecord)
    .map((part) => typeof part.text === "string" ? part.text.trim() : "")
    .filter((text) => text.length > 0)
    .join("\n")
}

function withServerSystemInstruction(payload: Record<string, unknown>): Record<string, unknown> {
  const clientInstruction = extractSystemInstructionText(payload)
  const combinedInstruction = clientInstruction.length > 0
    ? `${SERVER_SYSTEM_INSTRUCTION}\n\nClient task constraints:\n${clientInstruction}`
    : SERVER_SYSTEM_INSTRUCTION

  return {
    ...payload,
    system_instruction: {
      parts: [{ text: combinedInstruction.slice(0, MAX_PROMPT_TEXT_CHARS) }],
    },
  }
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

function isModelFallbackStatus(status: number): boolean {
  return [404, 408, 409, 429, 500, 502, 503, 504].includes(status)
}

async function callGemini(
  geminiApiKey: string,
  model: string,
  payload: Record<string, unknown>,
): Promise<Response> {
  return await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${geminiApiKey}`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    },
  )
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

  const geminiApiKey = Deno.env.get("GEMINI_API_KEY")
  if (!geminiApiKey) {
    return jsonResponse(req, { error: "Server configuration unavailable" }, 500)
  }

  let requestPhase = "reading_request_body"
  try {
    const requestBody = await req.text()
    if (requestBody.length === 0 || requestBody.length > MAX_BODY_BYTES) {
      return jsonResponse(req, { error: "Invalid request size" }, 413)
    }

    let payload: unknown
    requestPhase = "parsing_json_body"
    try {
      payload = JSON.parse(requestBody)
    } catch {
      return jsonResponse(req, { error: "Malformed JSON body" }, 400)
    }

    requestPhase = "validating_gemini_payload"
    if (!isValidGeminiPayload(payload)) {
      return jsonResponse(req, { error: "contents array is required" }, 400)
    }

    requestPhase = "checking_gemini_quota"
    const quotaAllowed = await consumeApiQuota(req, auth, "gemini", maxRequestsPerDay("GEMINI_DAILY_QUOTA", 50))
    if (!quotaAllowed) {
      return jsonResponse(req, { error: "Quota exhausted" }, 429)
    }

    const geminiModel = Deno.env.get("GEMINI_MODEL") ?? DEFAULT_GEMINI_MODEL
    const geminiFallbackModel = Deno.env.get("GEMINI_FALLBACK_MODEL") ?? DEFAULT_GEMINI_FALLBACK_MODEL
    const hardenedPayload = withServerSystemInstruction(payload as Record<string, unknown>)
    requestPhase = "calling_primary_gemini_model"
    let geminiResponse = await callGemini(geminiApiKey, geminiModel, hardenedPayload)
    if (!geminiResponse.ok && isModelFallbackStatus(geminiResponse.status)) {
      console.warn(`Primary model ${geminiModel} failed with ${geminiResponse.status}; retrying fallback model ${geminiFallbackModel}`)
      requestPhase = "calling_fallback_gemini_model"
      geminiResponse = await callGemini(geminiApiKey, geminiFallbackModel, hardenedPayload)
    }

    requestPhase = "reading_gemini_response"
    const responseText = await geminiResponse.text()
    if (!geminiResponse.ok) {
      const errorId = diagnosticId()
      console.error("Gemini upstream request failed", {
        id: errorId,
        phase: requestPhase,
        status: geminiResponse.status,
        statusText: geminiResponse.statusText,
        model: geminiResponse.url.includes(geminiFallbackModel) ? geminiFallbackModel : geminiModel,
        body: truncateForLog(responseText),
      })
      return jsonResponse(req, {
        ok: false,
        error: {
          type: "gemini_upstream_request_failed",
          status: geminiResponse.status,
          upstream_status: geminiResponse.status,
          message: "Gemini upstream request failed",
          upstream_body: sanitizedUpstreamBody(responseText),
          diagnostic_id: errorId,
        },
      }, geminiResponse.status)
    }

    return new Response(responseText, {
      status: geminiResponse.status,
      headers: corsHeaders,
    })
  } catch (error) {
    const errorId = diagnosticId()
    console.error("Gemini proxy internal error", {
      id: errorId,
      phase: requestPhase,
      ...errorToLog(error),
    })
    return jsonResponse(req, {
      error: "Gemini proxy request failed",
      detail: "Internal error",
      id: errorId,
    }, 502)
  }
})
