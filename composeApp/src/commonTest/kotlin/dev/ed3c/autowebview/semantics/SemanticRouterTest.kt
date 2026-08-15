package dev.ed3c.autowebview.semantics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticRouterTest {
    @Test
    fun tiesAreResolvedByStableCandidateIdentity() = runTest {
        val candidates = listOf(
            candidate("b", "kotlin webview bridge"),
            candidate("a", "kotlin webview bridge"),
        )

        val result = LexicalSemanticRouter().route(
            request(query = "kotlin webview", limit = 2),
            candidates,
        )

        assertEquals(listOf("a", "b"), result.routes.map(SemanticRoute::candidateId))
        assertEquals(result.routes[0].score, result.routes[1].score)
    }

    @Test
    fun fixedCorpusProducesPerfectTopOnePrecisionAndRecall() = runTest {
        val candidates = benchmarkCandidates()
        val fixtures = listOf(
            "kotlin webview privacy redaction" to setOf("kmp-privacy"),
            "openclaw replay backpressure reconnect" to setOf("openclaw-stream"),
            "testflight signed archive app attest" to setOf("ios-release"),
        )
        val router = LexicalSemanticRouter()

        fixtures.forEach { (query, expectedRelevant) ->
            val result = router.route(request(query, limit = 1), candidates)
            val actual = result.routes.mapTo(linkedSetOf(), SemanticRoute::candidateId)
            val truePositive = actual.intersect(expectedRelevant).size.toDouble()
            val precision = truePositive / actual.size.coerceAtLeast(1)
            val recall = truePositive / expectedRelevant.size.coerceAtLeast(1)

            assertEquals(expectedRelevant, actual, "Unexpected top result for query: $query")
            assertEquals(1.0, precision)
            assertEquals(1.0, recall)
        }
    }

    @Test
    fun contextBoundAndExpiredCandidatesAreRejectedBeforeRanking() = runTest {
        val result = LexicalSemanticRouter().route(
            request(
                query = "kotlin webview privacy",
                activeContextFingerprint = "page-current",
                nowEpochMs = 1_000,
                limit = 4,
            ),
            listOf(
                candidate(
                    id = "stale-exact",
                    content = "kotlin webview privacy",
                    contextFingerprint = "page-old",
                ),
                candidate(
                    id = "expired-exact",
                    content = "kotlin webview privacy",
                    contextFingerprint = "page-current",
                    expiresAtEpochMs = 1_000,
                ),
                candidate(
                    id = "fresh-partial",
                    content = "kotlin privacy boundary",
                    contextFingerprint = "page-current",
                    expiresAtEpochMs = 1_001,
                ),
            ),
        )

        assertEquals(listOf("fresh-partial"), result.routes.map(SemanticRoute::candidateId))
        assertEquals(2, result.metrics.rejectedStaleCandidates)
    }

    @Test
    fun boundCandidateWithoutCurrentFingerprintFailsClosed() = runTest {
        val result = LexicalSemanticRouter().route(
            request(query = "private cache", activeContextFingerprint = null),
            listOf(
                candidate(
                    id = "page-bound",
                    content = "private cache",
                    contextFingerprint = "page-1",
                ),
            ),
        )

        assertTrue(result.routes.isEmpty())
        assertEquals(1, result.metrics.rejectedStaleCandidates)
    }

    @Test
    fun resourceBudgetBoundsCandidatesCharactersAndResults() = runTest {
        val router = LexicalSemanticRouter(
            SemanticRoutingBudget(
                maximumCandidates = 2,
                maximumCandidateCharacters = 20,
                maximumTotalCharacters = 25,
                maximumResults = 1,
            ),
        )
        val result = router.route(
            request(query = "kotlin semantic routing", limit = 10),
            listOf(
                candidate("c", "kotlin semantic routing candidate c with long content"),
                candidate("a", "kotlin semantic routing candidate a with long content"),
                candidate("b", "kotlin semantic routing candidate b with long content"),
            ),
        )

        assertEquals(2, result.metrics.consideredCandidates)
        assertTrue(result.metrics.truncatedCandidates >= 2)
        assertTrue(result.metrics.processedCharacters <= 25)
        assertTrue(result.routes.size <= 1)
    }

    @Test
    fun duplicateIdsAreCountedAndDoNotProduceDuplicateRoutes() = runTest {
        val result = LexicalSemanticRouter().route(
            request(query = "semantic cache", limit = 5),
            listOf(
                candidate("same", "semantic cache first"),
                candidate("same", "semantic cache second"),
            ),
        )

        assertEquals(1, result.metrics.rejectedDuplicateCandidates)
        assertEquals(1, result.routes.size)
    }

    @Test
    fun failingPrimaryRouterFallsBackWithoutLeakingFailureMessage() = runTest {
        val primary = object : SemanticRouter {
            override suspend fun route(
                request: SemanticRoutingRequest,
                candidates: List<SemanticRouteCandidate>,
            ): SemanticRoutingResult = error("private endpoint and payload must not enter metrics")
        }
        val result = ResilientSemanticRouter(primary).route(
            request(query = "kotlin webview"),
            listOf(candidate("fallback", "kotlin webview")),
        )

        assertTrue(result.metrics.fallbackUsed)
        assertEquals("IllegalStateException", result.metrics.primaryFailureClass)
        assertFalse(result.metrics.primaryFailureClass.orEmpty().contains("private endpoint"))
        assertEquals(listOf("fallback"), result.routes.map(SemanticRoute::candidateId))
    }

    @Test
    fun cancellationIsNotConvertedIntoFallbackSuccess() = runTest {
        val primary = object : SemanticRouter {
            override suspend fun route(
                request: SemanticRoutingRequest,
                candidates: List<SemanticRouteCandidate>,
            ): SemanticRoutingResult = throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> {
            ResilientSemanticRouter(primary).route(
                request(query = "kotlin"),
                listOf(candidate("candidate", "kotlin")),
            )
        }
    }

    @Test
    fun requestAndResultContractsRoundTripThroughSerialization() = runTest {
        val json = Json { encodeDefaults = true }
        val request = request(
            query = "semantic routing",
            activeContextFingerprint = "page-42",
            nowEpochMs = 42,
            minimumScore = 0.1,
            limit = 3,
        )
        assertEquals(request, json.decodeFromString<SemanticRoutingRequest>(json.encodeToString(request)))

        val result = LexicalSemanticRouter().route(
            request,
            listOf(candidate("route", "semantic routing", contextFingerprint = "page-42")),
        )
        assertEquals(result, json.decodeFromString<SemanticRoutingResult>(json.encodeToString(result)))
    }

    @Test
    fun invalidBudgetsAndThresholdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            SemanticRoutingBudget(maximumCandidates = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            request(query = "invalid", minimumScore = 1.1)
        }
        assertFailsWith<IllegalArgumentException> {
            request(query = "invalid", limit = -1)
        }
    }

    private fun benchmarkCandidates() = listOf(
        candidate(
            "kmp-privacy",
            "Kotlin Multiplatform WebView privacy redaction sensitive input bridge",
        ),
        candidate(
            "openclaw-stream",
            "OpenClaw ordered stream replay expiry backpressure reconnect cancellation",
        ),
        candidate(
            "ios-release",
            "TestFlight signed archive Organizer validation App Attest physical iPhone",
        ),
        candidate(
            "cooking",
            "Tomato basil recipe and kitchen preparation",
        ),
    )

    private fun request(
        query: String,
        activeContextFingerprint: String? = null,
        nowEpochMs: Long = 100,
        minimumScore: Double = 0.0,
        limit: Int = 5,
    ) = SemanticRoutingRequest(
        query = query,
        activeContextFingerprint = activeContextFingerprint,
        nowEpochMs = nowEpochMs,
        minimumScore = minimumScore,
        limit = limit,
    )

    private fun candidate(
        id: String,
        content: String,
        contextFingerprint: String? = null,
        expiresAtEpochMs: Long? = null,
    ) = SemanticRouteCandidate(
        id = id,
        content = content,
        contextFingerprint = contextFingerprint,
        expiresAtEpochMs = expiresAtEpochMs,
    )
}
