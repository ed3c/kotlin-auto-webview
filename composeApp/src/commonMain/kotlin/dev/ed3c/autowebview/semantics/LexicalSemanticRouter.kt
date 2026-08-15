package dev.ed3c.autowebview.semantics

import kotlinx.coroutines.CancellationException
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt

class LexicalSemanticRouter(
    private val budget: SemanticRoutingBudget = SemanticRoutingBudget(),
) : SemanticRouter {
    override suspend fun route(
        request: SemanticRoutingRequest,
        candidates: List<SemanticRouteCandidate>,
    ): SemanticRoutingResult {
        val sortedCandidates = candidates.sortedBy(SemanticRouteCandidate::id)
        val uniqueCandidates = sortedCandidates.distinctBy(SemanticRouteCandidate::id)
        val duplicateCount = candidates.size - uniqueCandidates.size
        val boundedCandidates = uniqueCandidates.take(budget.maximumCandidates)

        var staleCount = 0
        var truncatedCount = (uniqueCandidates.size - boundedCandidates.size).coerceAtLeast(0)
        var processedCharacters = 0
        var remainingCharacters = budget.maximumTotalCharacters
        val accepted = mutableListOf<SemanticRoute>()

        for (candidate in boundedCandidates) {
            if (isStale(request, candidate)) {
                staleCount += 1
                continue
            }

            if (remainingCharacters <= 0) {
                truncatedCount += 1
                continue
            }

            val rawText = candidate.searchableText()
            val perCandidateBounded = rawText.take(budget.maximumCandidateCharacters)
            if (perCandidateBounded.length < rawText.length) truncatedCount += 1

            val searchableText = perCandidateBounded.take(remainingCharacters)
            if (searchableText.length < perCandidateBounded.length) truncatedCount += 1
            if (searchableText.isEmpty()) continue

            processedCharacters += searchableText.length
            remainingCharacters -= searchableText.length

            val score = normalizeScore(LexicalScoring.cosine(request.query, searchableText))
            if (score < request.minimumScore || score <= 0.0) continue

            accepted += SemanticRoute(
                candidateId = candidate.id,
                score = score,
                matchedTerms = LexicalScoring.matchedTerms(request.query, searchableText),
            )
        }

        val resultLimit = minOf(request.limit, budget.maximumResults)
        val routes = accepted
            .sortedWith(
                compareByDescending<SemanticRoute>(SemanticRoute::score)
                    .thenBy(SemanticRoute::candidateId),
            )
            .take(resultLimit)

        return SemanticRoutingResult(
            routes = routes,
            metrics = SemanticRoutingMetrics(
                inputCandidates = candidates.size,
                uniqueCandidates = uniqueCandidates.size,
                consideredCandidates = boundedCandidates.size,
                acceptedCandidates = accepted.size,
                rejectedDuplicateCandidates = duplicateCount,
                rejectedStaleCandidates = staleCount,
                truncatedCandidates = truncatedCount,
                processedCharacters = processedCharacters,
            ),
        )
    }

    private fun isStale(
        request: SemanticRoutingRequest,
        candidate: SemanticRouteCandidate,
    ): Boolean {
        val expired = candidate.expiresAtEpochMs?.let { expiry -> request.nowEpochMs >= expiry } ?: false
        val contextMismatch = candidate.contextFingerprint?.let { expected ->
            request.activeContextFingerprint != expected
        } ?: false
        return expired || contextMismatch
    }

    private fun SemanticRouteCandidate.searchableText(): String = buildString {
        append(title)
        append(' ')
        append(summary)
        append(' ')
        append(content)
        if (tags.isNotEmpty()) {
            append(' ')
            append(tags.sorted().joinToString(" "))
        }
    }

    private fun normalizeScore(score: Double): Double =
        (score.coerceIn(0.0, 1.0) * SCORE_SCALE).roundToLong().toDouble() / SCORE_SCALE

    private companion object {
        const val SCORE_SCALE = 1_000_000_000.0
    }
}

class ResilientSemanticRouter(
    private val primary: SemanticRouter,
    private val fallback: SemanticRouter = LexicalSemanticRouter(),
) : SemanticRouter {
    override suspend fun route(
        request: SemanticRoutingRequest,
        candidates: List<SemanticRouteCandidate>,
    ): SemanticRoutingResult = try {
        primary.route(request, candidates)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        val fallbackResult = fallback.route(request, candidates)
        fallbackResult.copy(
            metrics = fallbackResult.metrics.copy(
                fallbackUsed = true,
                primaryFailureClass = failure::class.simpleName ?: "Throwable",
            ),
        )
    }
}

internal object LexicalScoring {
    private val tokenRegex = Regex("[\\p{L}\\p{N}_-]{2,}")

    fun cosine(left: String, right: String): Double {
        val a = vector(left)
        val b = vector(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0

        var dot = 0.0
        for ((token, weight) in a) {
            dot += weight * (b[token] ?: 0.0)
        }

        val normA = sqrt(a.values.sumOf { value -> value * value })
        val normB = sqrt(b.values.sumOf { value -> value * value })
        if (normA == 0.0 || normB == 0.0) return 0.0
        return (dot / (normA * normB)).coerceIn(0.0, 1.0)
    }

    fun matchedTerms(left: String, right: String): List<String> =
        (tokens(left) intersect tokens(right)).sorted()

    private fun vector(text: String): Map<String, Double> =
        tokenRegex.findAll(text.lowercase())
            .map { match -> match.value }
            .groupingBy { token -> token }
            .eachCount()
            .mapValues { (_, count) -> 1.0 + ln(count.toDouble()) }

    private fun tokens(text: String): Set<String> =
        tokenRegex.findAll(text.lowercase()).mapTo(linkedSetOf()) { match -> match.value }
}
