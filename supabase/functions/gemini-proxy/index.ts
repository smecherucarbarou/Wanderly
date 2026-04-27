const MAX_BODY_BYTES = 3 * 1024 * 1024

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

function isValidGeminiPayload(payload: unknown): boolean {
  if (!isRecord(payload)) return false
  const contents = payload.contents
  return Array.isArray(contents) && contents.length > 0 && contents.length <= 16
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

  const geminiApiKey = Deno.env.get("GEMINI_API_KEY")
  if (!geminiApiKey) {
    return jsonResponse(req, { error: "Missing GEMINI_API_KEY secret" }, 500)
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

    if (!isValidGeminiPayload(payload)) {
      return jsonResponse(req, { error: "contents array is required" }, 400)
    }

    const geminiResponse = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=${geminiApiKey}`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: requestBody,
      }
    )

    const responseText = await geminiResponse.text()
    if (!geminiResponse.ok) {
      return jsonResponse(req, { error: "Gemini upstream request failed" }, geminiResponse.status)
    }

    return new Response(responseText, {
      status: geminiResponse.status,
      headers: corsHeaders,
    })
  } catch {
    return jsonResponse(req, { error: "Gemini proxy request failed" }, 500)
  }
})
