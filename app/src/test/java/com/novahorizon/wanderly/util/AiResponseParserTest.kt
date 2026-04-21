package com.novahorizon.wanderly.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiResponseParserTest {

    @Test
    fun `extracts first json object from surrounding text`() {
        val response = "Sure, here you go:\n{\"missionText\":\"Go\",\"targetName\":\"Cafe\"}\nThanks!"

        assertEquals(
            "{\"missionText\":\"Go\",\"targetName\":\"Cafe\"}",
            AiResponseParser.extractFirstJsonObject(response)
        )
    }

    @Test
    fun `extracts first json array from surrounding text`() {
        val response = "```json\n[{\"candidateIndex\":1},{\"candidateIndex\":2}]\n```"

        assertEquals(
            "[{\"candidateIndex\":1},{\"candidateIndex\":2}]",
            AiResponseParser.extractFirstJsonArray(response)
        )
    }

    @Test
    fun `returns null when requested json block is missing`() {
        assertNull(AiResponseParser.extractFirstJsonObject("plain text"))
        assertNull(AiResponseParser.extractFirstJsonArray("{\"not\":\"an array\"}"))
    }

    @Test
    fun `extracts only the first json object when multiple objects exist`() {
        val response = "{\"first\":1}\n\nSome text\n\n{\"second\":2}"

        assertEquals(
            "{\"first\":1}",
            AiResponseParser.extractFirstJsonObject(response)
        )
    }

    @Test
    fun `extracts only the first json array when multiple arrays exist`() {
        val response = "[{\"first\":1}]\n\nSome text\n\n[{\"second\":2}]"

        assertEquals(
            "[{\"first\":1}]",
            AiResponseParser.extractFirstJsonArray(response)
        )
    }
}
