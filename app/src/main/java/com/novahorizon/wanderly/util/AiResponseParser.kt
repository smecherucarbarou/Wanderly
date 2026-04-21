package com.novahorizon.wanderly.util

object AiResponseParser {
    private val jsonObjectRegex = Regex("(?s)\\{.*}")
    private val jsonArrayRegex = Regex("(?s)\\[.*]")

    fun extractFirstJsonObject(response: String): String? = jsonObjectRegex.find(response)?.value

    fun extractFirstJsonArray(response: String): String? = jsonArrayRegex.find(response)?.value
}
