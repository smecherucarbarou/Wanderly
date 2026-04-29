package com.novahorizon.wanderly.data.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionCandidateParserTest {

    @Test
    fun `parses valid candidates json`() {
        val raw = """
            {
              "candidates": [
                {
                  "name": "Coloana Infinitului",
                  "localName": "Coloana Infinitului",
                  "query": "Coloana Infinitului Targu Jiu Romania",
                  "category": "monument",
                  "reason": "Well-known public landmark",
                  "expectedCity": "Targu Jiu",
                  "priority": 1
                }
              ]
            }
        """.trimIndent()

        val candidates = MissionCandidateParser.parseMissionPlaceCandidates(raw)

        assertEquals(1, candidates.size)
        assertEquals("Coloana Infinitului", candidates.single().name)
        assertEquals("Coloana Infinitului Targu Jiu Romania", candidates.single().query)
        assertEquals("monument", candidates.single().category)
        assertEquals(1, candidates.single().priority)
    }

    @Test
    fun `drops candidates missing name or query`() {
        val raw = """
            {
              "candidates": [
                {"name":"Only Name"},
                {"query":"Only Query Targu Jiu"},
                {"name":"Valid Park","query":"Valid Park Targu Jiu"}
              ]
            }
        """.trimIndent()

        val candidates = MissionCandidateParser.parseMissionPlaceCandidates(raw)

        assertEquals(listOf("Valid Park"), candidates.map { it.name })
    }

    @Test
    fun `deduplicates candidates by normalized name and query`() {
        val raw = """
            {
              "candidates": [
                {"name":"The Infinity Column","query":"The Infinity Column Targu Jiu"},
                {"name":" the  infinity   column ","query":"the infinity column targu jiu"},
                {"name":"Central Park","query":"Central Park Targu Jiu"}
              ]
            }
        """.trimIndent()

        val candidates = MissionCandidateParser.parseMissionPlaceCandidates(raw)

        assertEquals(listOf("The Infinity Column", "Central Park"), candidates.map { it.name })
    }

    @Test
    fun `limits parsed candidates to fifteen`() {
        val rawCandidates = (1..20).joinToString(",") { index ->
            """{"name":"Place $index","query":"Place $index Targu Jiu"}"""
        }

        val candidates = MissionCandidateParser.parseMissionPlaceCandidates(
            """{"candidates":[$rawCandidates]}"""
        )

        assertEquals(15, candidates.size)
        assertEquals("Place 15", candidates.last().name)
    }

    @Test
    fun `handles malformed and prose responses without crash`() {
        assertTrue(MissionCandidateParser.parseMissionPlaceCandidates("not json").isEmpty())
        assertTrue(
            MissionCandidateParser.parseMissionPlaceCandidates(
                """Sure! {"place":"Coloana Infinitului"}"""
            ).isEmpty()
        )
        assertTrue(
            MissionCandidateParser.parseMissionPlaceCandidates(
                """{"candidates":[]}"""
            ).isEmpty()
        )
    }
}
