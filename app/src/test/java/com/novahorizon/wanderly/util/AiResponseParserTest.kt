package com.novahorizon.wanderly.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `photo verification accepts yes prefix only`() {
        val result = AiResponseParser.parsePhotoVerification("YES: The storefront sign is visible.")

        assertTrue(result.verified)
        assertEquals("The storefront sign is visible.", result.reason)
    }

    @Test
    fun `photo verification rejects no prefix even when yes appears later`() {
        val result = AiResponseParser.parsePhotoVerification("NO: This is not the target. YES appears on a sign.")

        assertFalse(result.verified)
        assertEquals("This is not the target. YES appears on a sign.", result.reason)
    }

    @Test
    fun `photo verification rejects malformed and empty text`() {
        assertFalse(AiResponseParser.parsePhotoVerification("").verified)
        assertFalse(AiResponseParser.parsePhotoVerification("The answer is probably yes").verified)
        assertFalse(AiResponseParser.parsePhotoVerification("maybe").verified)
    }

    @Test
    fun `photo verification safely normalizes lowercase yes prefix`() {
        val result = AiResponseParser.parsePhotoVerification("yes: correct entrance")

        assertTrue(result.verified)
        assertEquals("correct entrance", result.reason)
    }

    @Test
    fun `photo verification accepts verified json`() {
        val result = AiResponseParser.parsePhotoVerification(
            """{"verified":true,"reason":"The image clearly matches the mission."}"""
        )

        assertTrue(result.verified)
        assertEquals("The image clearly matches the mission.", result.reason)
    }

    @Test
    fun `photo verification rejects false json and invalid json`() {
        assertFalse(
            AiResponseParser.parsePhotoVerification(
                """{"verified":false,"reason":"Wrong place."}"""
            ).verified
        )
        assertFalse(AiResponseParser.parsePhotoVerification("""{"verified":true""").verified)
    }
}
