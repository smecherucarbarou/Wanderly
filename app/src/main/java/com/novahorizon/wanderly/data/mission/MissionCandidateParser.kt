package com.novahorizon.wanderly.data.mission

import com.novahorizon.wanderly.BuildConfig
import com.novahorizon.wanderly.observability.AppLogger
import com.novahorizon.wanderly.observability.LogRedactor
import com.novahorizon.wanderly.util.AiResponseParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer

object MissionCandidateParser {
    private const val TAG = "MissionCandidateParser"
    private const val MAX_CANDIDATES = 15
    private val json = Json { ignoreUnknownKeys = true }

    fun parseMissionPlaceCandidates(raw: String): List<MissionPlaceCandidate> {
        val jsonObject = AiResponseParser.extractFirstJsonObject(raw) ?: return emptyList()
        val envelope = runCatching {
            json.decodeFromString<CandidateEnvelope>(jsonObject)
        }.onFailure { error ->
            logWarning("Mission candidate JSON could not be parsed", error)
        }.getOrNull() ?: return emptyList()

        val seen = mutableSetOf<String>()
        return envelope.candidates.asSequence()
            .mapNotNull(::sanitizeCandidate)
            .filter { candidate ->
                seen.add("${normalize(candidate.name)}|${normalize(candidate.query)}")
            }
            .take(MAX_CANDIDATES)
            .toList()
    }

    private fun sanitizeCandidate(candidate: RawCandidate): MissionPlaceCandidate? {
        val name = normalizeWhitespace(candidate.name.orEmpty())
        val query = normalizeWhitespace(candidate.query.orEmpty())
        if (name.isBlank() || query.isBlank()) return null
        return MissionPlaceCandidate(
            name = name,
            localName = candidate.localName?.let(::normalizeWhitespace)?.takeIf { it.isNotBlank() },
            query = query,
            category = candidate.category?.let(::normalizeWhitespace)?.takeIf { it.isNotBlank() },
            reason = candidate.reason?.let(::normalizeWhitespace)?.takeIf { it.isNotBlank() },
            expectedCity = candidate.expectedCity?.let(::normalizeWhitespace)?.takeIf { it.isNotBlank() },
            priority = candidate.priority.coerceAtLeast(0)
        )
    }

    private fun normalizeWhitespace(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")

    internal fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+")
            .replace(decomposed, "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun logWarning(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) {
            runCatching {
                AppLogger.w(TAG, "${LogRedactor.redact(message)} [${throwable.javaClass.simpleName}]")
            }
        }
    }

    @Serializable
    private data class CandidateEnvelope(
        val candidates: List<RawCandidate> = emptyList()
    )

    @Serializable
    private data class RawCandidate(
        val name: String? = null,
        val localName: String? = null,
        val query: String? = null,
        val category: String? = null,
        val reason: String? = null,
        val expectedCity: String? = null,
        val priority: Int = 0
    )
}
