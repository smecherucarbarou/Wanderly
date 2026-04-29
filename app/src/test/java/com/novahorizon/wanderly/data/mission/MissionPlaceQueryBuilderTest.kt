package com.novahorizon.wanderly.data.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionPlaceQueryBuilderTest {

    @Test
    fun `builds city bound query variants and limits queries per candidate`() {
        val candidate = MissionPlaceCandidate(
            name = "Coloana Infinitului",
            localName = "Coloana Infinitului",
            query = "Coloana Infinitului Targu Jiu",
            category = "monument"
        )

        val queries = MissionPlaceQueryBuilder.buildQueries(
            candidate = candidate,
            city = "Targu Jiu",
            countryRegion = "Romania"
        )

        assertEquals(
            listOf(
                "Coloana Infinitului Targu Jiu",
                "Coloana Infinitului Targu Jiu Romania"
            ),
            queries
        )
        assertTrue(queries.size <= MissionPlaceQueryBuilder.MAX_QUERIES_PER_CANDIDATE)
    }

    @Test
    fun `validation plan caps total places calls and reserves deterministic fallback`() {
        val candidates = (1..20).map { index ->
            MissionPlaceCandidate(
                name = "Candidate $index",
                query = "Candidate $index Targu Jiu",
                priority = index
            )
        }

        val plan = MissionPlaceQueryBuilder.buildValidationPlan(
            candidates = candidates,
            city = "Targu Jiu",
            countryRegion = "Romania"
        )

        assertTrue(plan.size <= MissionPlaceQueryBuilder.MAX_TOTAL_PLACES_QUERIES_PER_MISSION)
        assertTrue(plan.any { it.isDeterministicFallback })
        assertTrue(plan.count { !it.isDeterministicFallback } > 1)
        assertTrue(
            plan.filterNot { it.isDeterministicFallback }
                .map { it.candidate }
                .distinct()
                .size <= MissionPlaceQueryBuilder.MAX_AI_CANDIDATES_TO_VALIDATE
        )
    }
}
