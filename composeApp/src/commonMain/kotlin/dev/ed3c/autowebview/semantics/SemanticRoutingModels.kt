package dev.ed3c.autowebview.semantics

import kotlinx.serialization.Serializable

@Serializable
data class SemanticRouteCandidate(
    val id: String,
    val title: String = "",
    val summary: String = "",
    val content: String,
    val tags: Set<String> = emptySet(),
    val contextFingerprint: String? = null,
    val expiresAtEpochMs: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Semantic candidate id cannot be blank" }
    }
}

@Serializable
data class SemanticRoutingRequest(
    val query: String,
    val activeContextFingerprint: String? = null,
    val nowEpochMs: Long,
    val minimumScore: Double = 0.0,
    val limit: Int = 5,
) {
    init {
        require(minimumScore in 0.0..1.0) { "Minimum score must be between 0 and 1" }
        require(limit >= 0) { "Result limit cannot be negative" }
    }
}

@Serializable
data class SemanticRoutingBudget(
    val maximumCandidates: Int = 128,
    val maximumCandidateCharacters: Int = 4_096,
    val maximumTotalCharacters: Int = 65_536,
    val maximumResults: Int = 8,
) {
    init {
        require(maximumCandidates > 0) { "Candidate budget must be positive" }
        require(maximumCandidateCharacters > 0) { "Per-candidate character budget must be positive" }
        require(maximumTotalCharacters > 0) { "Total character budget must be positive" }
        require(maximumResults >= 0) { "Result budget cannot be negative" }
    }
}

@Serializable
enum class SemanticRouteSource {
    LEXICAL_BASELINE,
}

@Serializable
data class SemanticRoute(
    val candidateId: String,
    val score: Double,
    val matchedTerms: List<String>,
    val source: SemanticRouteSource = SemanticRouteSource.LEXICAL_BASELINE,
)

@Serializable
data class SemanticRoutingMetrics(
    val inputCandidates: Int,
    val uniqueCandidates: Int,
    val consideredCandidates: Int,
    val acceptedCandidates: Int,
    val rejectedDuplicateCandidates: Int,
    val rejectedStaleCandidates: Int,
    val truncatedCandidates: Int,
    val processedCharacters: Int,
    val fallbackUsed: Boolean = false,
    val primaryFailureClass: String? = null,
)

@Serializable
data class SemanticRoutingResult(
    val routes: List<SemanticRoute>,
    val metrics: SemanticRoutingMetrics,
)

interface SemanticRouter {
    suspend fun route(
        request: SemanticRoutingRequest,
        candidates: List<SemanticRouteCandidate>,
    ): SemanticRoutingResult
}
