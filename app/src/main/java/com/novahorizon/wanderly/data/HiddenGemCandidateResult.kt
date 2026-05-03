package com.novahorizon.wanderly.data

sealed class HiddenGemCandidateResult {
    data class Success(val candidates: List<DiscoveredPlace>) : HiddenGemCandidateResult()

    data class Error(
        val reason: Reason,
        val statusCode: Int? = null,
        val message: String? = null
    ) : HiddenGemCandidateResult()

    enum class Reason {
        BadRequest,
        Unauthorized,
        Forbidden,
        Server,
        Network,
        Timeout,
        Parse,
        Unknown
    }
}
