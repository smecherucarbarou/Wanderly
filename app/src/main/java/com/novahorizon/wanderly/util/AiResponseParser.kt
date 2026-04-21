package com.novahorizon.wanderly.util

object AiResponseParser {
    fun extractFirstJsonObject(response: String): String? = extractBalancedJson(response, '{', '}')

    fun extractFirstJsonArray(response: String): String? = extractBalancedJson(response, '[', ']')

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
}
