package com.novahorizon.wanderly.data.mission

object MissionPlaceQueryBuilder {
    const val MAX_AI_CANDIDATES_TO_VALIDATE = 10
    const val MAX_QUERIES_PER_CANDIDATE = 2
    const val MAX_TOTAL_PLACES_QUERIES_PER_MISSION = 20
    private const val DETERMINISTIC_FALLBACK_QUERY_BUDGET = 12

    data class ValidationQuery(
        val candidate: MissionPlaceCandidate,
        val query: String,
        val isDeterministicFallback: Boolean
    )

    fun buildQueries(
        candidate: MissionPlaceCandidate,
        city: String,
        countryRegion: String?
    ): List<String> {
        val cityName = city.trim()
        val country = countryRegion?.trim().orEmpty()
        val name = candidate.name.trim()
        val category = candidate.category?.trim()?.takeIf { it.isNotBlank() } ?: "landmark"
        return listOfNotNull(
            candidate.query.trim().takeIf { it.isNotBlank() },
            joinQuery(name, cityName),
            candidate.localName?.let { joinQuery(it, cityName) },
            joinQuery(name, cityName, country),
            "$category in $cityName".takeIf { cityName.isNotBlank() }
        )
            .map { normalizeWhitespace(it) }
            .filter { it.isNotBlank() }
            .distinctBy { MissionCandidateParser.normalize(it) }
            .take(MAX_QUERIES_PER_CANDIDATE)
    }

    fun buildValidationPlan(
        candidates: List<MissionPlaceCandidate>,
        city: String,
        countryRegion: String?
    ): List<ValidationQuery> {
        return buildAiValidationPlan(candidates, city, countryRegion) +
            buildDeterministicFallbackPlan(city, countryRegion)
                .take(MAX_TOTAL_PLACES_QUERIES_PER_MISSION - aiQueryBudget())
    }

    fun buildAiValidationPlan(
        candidates: List<MissionPlaceCandidate>,
        city: String,
        countryRegion: String?
    ): List<ValidationQuery> {
        val plan = mutableListOf<ValidationQuery>()
        val seenQueries = mutableSetOf<String>()
        val orderedCandidates = candidates
            .take(MAX_AI_CANDIDATES_TO_VALIDATE)
            .sortedWith(compareBy<MissionPlaceCandidate> { if (it.priority > 0) it.priority else Int.MAX_VALUE })
        for (candidate in orderedCandidates) {
            for (query in buildQueries(candidate, city, countryRegion)) {
                if (plan.size >= aiQueryBudget()) return plan
                if (seenQueries.add(MissionCandidateParser.normalize(query))) {
                    plan += ValidationQuery(candidate, query, isDeterministicFallback = false)
                }
            }
        }
        return plan
    }

    fun buildDeterministicFallbackPlan(
        city: String,
        countryRegion: String?
    ): List<ValidationQuery> {
        val cityName = city.trim()
        if (cityName.isBlank()) return emptyList()
        val country = countryRegion?.trim().orEmpty()
        return listOf(
            "tourist attractions",
            "parks",
            "monuments",
            "museums",
            "public art",
            "historic landmarks",
            "scenic viewpoints",
            "cultural landmarks",
            "walking-friendly places",
            "hidden gems",
            "points of interest",
            "landmarks near city center"
        ).map { category ->
            val query = joinQuery("$category in", cityName, country)
            ValidationQuery(
                candidate = MissionPlaceCandidate(
                    name = "$category in $cityName",
                    query = query,
                    category = category,
                    expectedCity = cityName
                ),
                query = query,
                isDeterministicFallback = true
            )
        }.take(DETERMINISTIC_FALLBACK_QUERY_BUDGET)
    }

    fun aiQueryBudget(): Int =
        MAX_TOTAL_PLACES_QUERIES_PER_MISSION - DETERMINISTIC_FALLBACK_QUERY_BUDGET

    private fun joinQuery(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString(" ")

    private fun normalizeWhitespace(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")
}
