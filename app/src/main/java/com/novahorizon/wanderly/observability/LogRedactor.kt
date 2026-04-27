package com.novahorizon.wanderly.observability

object LogRedactor {
    private const val MAX_LOG_VALUE_LENGTH = 200
    private val localUriPattern = Regex("""\b(?:file|content)://\S+""", RegexOption.IGNORE_CASE)
    private val urlPattern = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val bearerTokenPattern = Regex("""(?i)Bearer\s+[A-Za-z0-9._~+/-]+=*""")
    private val jwtPattern = Regex("""\b[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}\b""")
    private val uuidPattern = Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE)
    private val emailPattern = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")

    fun redact(value: String?): String {
        val redacted = value
            ?.let { localUriPattern.replace(it, "[redacted-uri]") }
            ?.let { urlPattern.replace(it, "[redacted-url]") }
            ?.let { bearerTokenPattern.replace(it, "Bearer [redacted-token]") }
            ?.let { jwtPattern.replace(it, "[redacted-jwt]") }
            ?.let { uuidPattern.replace(it, "[redacted-id]") }
            ?.let { emailPattern.replace(it, "[redacted-email]") }
            ?: "null"

        return redacted.take(MAX_LOG_VALUE_LENGTH).let { bounded ->
            if (redacted.length > MAX_LOG_VALUE_LENGTH) "$bounded..." else bounded
        }
    }
}
