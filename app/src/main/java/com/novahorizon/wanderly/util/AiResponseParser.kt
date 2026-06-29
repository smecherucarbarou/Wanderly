package com.novahorizon.wanderly.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object AiResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun extractFirstJsonObject(response: String): String? = extractBalancedJson(response, '{', '}')

    fun extractFirstJsonArray(response: String): String? = extractBalancedJson(response, '[', ']')

    fun parsePhotoVerification(response: String): PhotoVerificationDecision {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) {
            return PhotoVerificationDecision(
                verified = false,
                reason = null
            )
        }

        val jsonDecision = parsePhotoVerificationJson(trimmed)
        if (jsonDecision != null) {
            return jsonDecision
        }

        val prefix = trimmed.substringBefore(":", missingDelimiterValue = "")
            .trim()
            .uppercase()
        val reason = trimmed.substringAfter(":", missingDelimiterValue = "")
            .trim()
            .ifBlank { null }

        return when (prefix) {
            "YES" -> PhotoVerificationDecision(
                verified = true,
                reason = reason
            )
            "NO" -> PhotoVerificationDecision(
                verified = false,
                reason = reason
            )
            else -> PhotoVerificationDecision(
                verified = false,
                reason = null
            )
        }
    }

    private fun extractBalancedJson(response: String, openingChar: Char, closingChar: Char): String? {
        val startIndex = response.indexOf(openingChar)
        if (startIndex == -1) {
            return null
        }

        var depth = 0
        var insideString = false
        var isEscaped = false

        for (index in startIndex until response.length) {
            val current = response[index]

            if (insideString) {
                when {
                    isEscaped -> isEscaped = false
                    current == '\\' -> isEscaped = true
                    current == '"' -> insideString = false
                }
                continue
            }

            when (current) {
                '"' -> insideString = true
                openingChar -> depth++
                closingChar -> {
                    depth--
                    if (depth == 0) {
                        return response.substring(startIndex, index + 1)
                    }
                }
            }
        }

        return null
    }

    private fun parsePhotoVerificationJson(response: String): PhotoVerificationDecision? {
        val jsonObjectText = extractFirstJsonObject(response) ?: return null
        return runCatching {
            val parsed = json.parseToJsonElement(jsonObjectText).jsonObject
            val verified = parsed["verified"]?.jsonPrimitive?.booleanOrNull ?: return null
            PhotoVerificationDecision(
                verified = verified,
                reason = parsed["reason"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            )
        }.getOrNull()
    }
}

data class PhotoVerificationDecision(
    val verified: Boolean,
    val reason: String?
)
