const corsHeaders = {
  "Content-Type": "application/json",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Method not allowed" }), {
      status: 405,
      headers: corsHeaders,
    })
  }

  const placesApiKey = Deno.env.get("MAPS_API_KEY")
  if (!placesApiKey) {
    return new Response(JSON.stringify({ error: "Missing MAPS_API_KEY secret" }), {
      status: 500,
      headers: corsHeaders,
    })
  }

  try {
    const payload = await req.json()
    const fieldMask = typeof payload?.fieldMask === "string" ? payload.fieldMask : ""
    const body = payload?.body

    if (!fieldMask || !body) {
      return new Response(JSON.stringify({ error: "fieldMask and body are required" }), {
        status: 400,
        headers: corsHeaders,
      })
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
    return new Response(JSON.stringify({ error: message }), {
      status: 500,
      headers: corsHeaders,
    })
  }
})
