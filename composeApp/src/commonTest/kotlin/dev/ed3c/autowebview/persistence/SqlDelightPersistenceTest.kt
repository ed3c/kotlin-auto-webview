package dev.ed3c.autowebview.persistence

import dev.ed3c.autowebview.domain.AuditEvent
import dev.ed3c.autowebview.domain.SemanticCacheRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqlDelightPersistenceTest {
    @Test
    fun cacheRankingIsDeterministicAcrossInputOrder() {
        val newer = record("b", 20, "kotlin multiplatform browser cache")
        val older = record("a", 10, "kotlin multiplatform browser cache")

        val forward = PersistentCacheRanking.rank(listOf(older, newer), "kotlin browser", 5)
        val reverse = PersistentCacheRanking.rank(listOf(newer, older), "kotlin browser", 5)

        assertEquals(listOf("b", "a"), forward.map { it.record.id })
        assertEquals(forward.map { it.record.id }, reverse.map { it.record.id })
        assertEquals(forward.map { it.relevance }, reverse.map { it.relevance })
    }

    @Test
    fun cacheRankingHonorsNonNegativeLimit() {
        val matches = PersistentCacheRanking.rank(
            listOf(record("a", 1, "semantic cache")),
            "semantic",
            -1,
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun cacheSanitizerDropsUrlSecretsAndRedactsContent() {
        val safe = PersistenceSanitizer().sanitize(
            SemanticCacheRecord(
                id = "record-1",
                sourceUrl = "https://example.invalid/page?token=private-token#secret",
                title = "token=abcdef123456",
                summary = "password: hunter2-secret",
                content = "card 4111 1111 1111 1111",
                createdAtEpochMs = 1,
                tags = setOf("authorization=Bearer.secret-value", "safe"),
            ),
        )

        assertEquals("https://example.invalid/page", safe.sourceUrl)
        assertFalse(safe.title.contains("abcdef123456"))
        assertFalse(safe.summary.contains("hunter2-secret"))
        assertFalse(safe.content.contains("4111"))
        assertTrue(safe.tags.any { it == "safe" })
        assertTrue(safe.tags.none { "secret-value" in it })
    }

    @Test
    fun auditSanitizerRedactsSensitiveKeysAndValues() {
        val event = AuditEvent(
            atEpochMs = 123,
            category = "tool",
            message = "token=abcdef123456 operation completed",
            metadata = mapOf(
                "authorization" to "Bearer top-secret-value",
                "note" to "password: hunter2-secret",
                "safe" to "projection-ready",
            ),
        )

        val safe = AuditSanitizer().sanitize(event)

        assertFalse(safe.message.contains("abcdef123456"))
        assertEquals("[REDACTED]", safe.metadata.getValue("authorization"))
        assertFalse(safe.metadata.getValue("note").contains("hunter2-secret"))
        assertEquals("projection-ready", safe.metadata.getValue("safe"))
    }

    @Test
    fun auditSanitizerRedactsPaymentLikeNumbersAndPrivateKeys() {
        val safe = AuditSanitizer().sanitize(
            AuditEvent(
                atEpochMs = 1,
                category = "form",
                message = """
                    card 4111 1111 1111 1111
                    -----BEGIN TEST PRIVATE KEY-----
                    private-material
                    -----END TEST PRIVATE KEY-----
                """.trimIndent(),
            ),
        )

        assertTrue("[REDACTED]" in safe.message)
        assertFalse("4111" in safe.message)
        assertFalse("private-material" in safe.message)
    }

    @Test
    fun invalidRetentionBudgetsFailBeforeDatabaseMutation() {
        assertFailsWith<IllegalArgumentException> {
            PersistentMemoryPolicy(maximumCacheRecords = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PersistentMemoryPolicy(maximumAuditEvents = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PersistentMemoryPolicy(maximumQueryCandidates = 0)
        }
    }

    private fun record(id: String, createdAt: Long, content: String) = SemanticCacheRecord(
        id = id,
        sourceUrl = "https://example.invalid/$id",
        title = id,
        summary = content,
        content = content,
        createdAtEpochMs = createdAt,
        tags = setOf("test"),
    )
}
