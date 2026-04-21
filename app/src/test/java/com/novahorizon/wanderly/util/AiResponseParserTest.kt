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
}
