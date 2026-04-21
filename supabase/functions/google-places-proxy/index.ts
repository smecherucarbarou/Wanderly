const MAX_FIELD_MASK_LENGTH = 256

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

  const placesApiKey = Deno.env.get("MAPS_API_KEY")
  if (!placesApiKey) {
    return jsonResponse(req, { error: "Missing MAPS_API_KEY secret" }, 500)
  }

  try {
    const payload = await req.json()
    const fieldMask = typeof payload?.fieldMask === "string" ? payload.fieldMask : ""
    const body = payload?.body

    if (
      !fieldMask ||
      !body ||
      fieldMask.length > MAX_FIELD_MASK_LENGTH ||
      typeof body?.textQuery !== "string" ||
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
    return new Response(responseText, {
      status: response.status,
      headers: corsHeaders,
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown Places proxy error"
    return jsonResponse(req, { error: message }, 500)
  }
})
