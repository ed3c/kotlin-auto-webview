package dev.ed3c.autowebview.persistence

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.async.coroutines.awaitMigrate
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ed3c.autowebview.domain.AuditEvent
import dev.ed3c.autowebview.domain.SemanticCacheRecord
import dev.ed3c.autowebview.persistence.db.AppDatabase
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlDelightDesktopIntegrationTest {
    @Test
    fun cacheAndAuditSurviveFileBackedReopen() = runTest {
        val path = Files.createTempFile("kotlin-auto-webview-memory", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        try {
            JdbcSqliteDriver(url).use { firstDriver ->
                AppDatabase.Schema.awaitCreate(firstDriver)
                val cache = SqlDelightSemanticCache(firstDriver, nowEpochMs = { 200 })
                val audit = SqlDelightAuditEvidenceStore(firstDriver)
                cache.put(record("persisted", 100, "persistent semantic cache"))
                audit.append(
                    AuditEvent(
                        atEpochMs = 101,
                        category = "memory",
                        message = "record persisted",
                        metadata = mapOf("state" to "stored"),
                    ),
                )
            }

            JdbcSqliteDriver(url).use { reopenedDriver ->
                val cache = SqlDelightSemanticCache(reopenedDriver, nowEpochMs = { 300 })
                val audit = SqlDelightAuditEvidenceStore(reopenedDriver)

                assertEquals(
                    listOf("persisted"),
                    cache.query("semantic cache", 5).map { it.record.id },
                )
                assertEquals(1L, cache.storedRecordCount())
                assertEquals("record persisted", audit.recent().single().message)
                assertEquals(1L, audit.storedEventCount())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun retentionAndExplicitDeletionRemainBounded() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            AppDatabase.Schema.awaitCreate(driver)
            var now = 100L
            val policy = PersistentMemoryPolicy(
                maximumCacheRecords = 2,
                maximumAuditEvents = 2,
                maximumQueryCandidates = 8,
            )
            val cache = SqlDelightSemanticCache(driver, policy, nowEpochMs = { now++ })
            val audit = SqlDelightAuditEvidenceStore(driver, policy)

            cache.put(record("a", 1, "semantic cache alpha"))
            cache.put(record("b", 2, "semantic cache beta"))
            cache.put(record("c", 3, "semantic cache gamma"))
            assertEquals(2L, cache.storedRecordCount())
            assertEquals(listOf("c", "b"), cache.query("semantic cache", 10).map { it.record.id })

            cache.remove("b")
            assertEquals(1L, cache.storedRecordCount())
            cache.clear()
            assertEquals(0L, cache.storedRecordCount())

            audit.append(AuditEvent(1, "audit", "one"))
            audit.append(AuditEvent(2, "audit", "two"))
            audit.append(AuditEvent(3, "audit", "three"))
            assertEquals(2L, audit.storedEventCount())
            assertEquals(listOf("three", "two"), audit.recent().map(AuditEvent::message))

            audit.pruneBefore(3)
            assertEquals(listOf("three"), audit.recent().map(AuditEvent::message))
        }
    }

    @Test
    fun migrationFromVersionOnePreservesCacheAndIntroducesAuditStore() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            driver.await(
                identifier = null,
                sql = """
                    CREATE TABLE semantic_cache_record (
                      id TEXT NOT NULL PRIMARY KEY,
                      source_url TEXT NOT NULL,
                      title TEXT NOT NULL,
                      summary TEXT NOT NULL,
                      content TEXT NOT NULL,
                      created_at_epoch_ms INTEGER NOT NULL,
                      tags_json TEXT NOT NULL
                    )
                """.trimIndent(),
                parameters = 0,
            )
            driver.await(
                identifier = null,
                sql = """
                    CREATE INDEX semantic_cache_created_idx
                    ON semantic_cache_record(created_at_epoch_ms DESC, id ASC)
                """.trimIndent(),
                parameters = 0,
            )
            driver.await(
                identifier = null,
                sql = """
                    INSERT INTO semantic_cache_record(
                      id, source_url, title, summary, content, created_at_epoch_ms, tags_json
                    ) VALUES ('legacy', 'https://example.invalid/legacy', 'Legacy', 'legacy summary',
                              'legacy semantic cache', 42, '["legacy"]')
                """.trimIndent(),
                parameters = 0,
            )

            AppDatabase.Schema.awaitMigrate(driver, oldVersion = 1, newVersion = 2)

            val cache = SqlDelightSemanticCache(driver, nowEpochMs = { 100 })
            val audit = SqlDelightAuditEvidenceStore(driver)
            assertEquals(listOf("legacy"), cache.query("legacy cache", 5).map { it.record.id })

            audit.append(AuditEvent(50, "migration", "audit table available"))
            assertEquals("audit table available", audit.recent().single().message)
        }
    }

    @Test
    fun malformedSerializedFieldsDegradeWithoutLeakingSecrets() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            AppDatabase.Schema.awaitCreate(driver)
            driver.await(
                identifier = null,
                sql = """
                    INSERT INTO semantic_cache_record(
                      id, source_url, title, summary, content, created_at_epoch_ms,
                      last_accessed_at_epoch_ms, tags_json
                    ) VALUES ('corrupt', 'https://example.invalid/corrupt', 'Corrupt', 'summary',
                              'semantic cache', 1, 1, '{not-json')
                """.trimIndent(),
                parameters = 0,
            )
            driver.await(
                identifier = null,
                sql = """
                    INSERT INTO audit_event(at_epoch_ms, category, message, metadata_json)
                    VALUES (1, 'corrupt', 'safe message', '{not-json')
                """.trimIndent(),
                parameters = 0,
            )

            val cacheRecord = SqlDelightSemanticCache(driver, nowEpochMs = { 2 })
                .query("semantic cache", 1)
                .single()
                .record
            assertTrue(cacheRecord.tags.isEmpty())

            val auditEvent = SqlDelightAuditEvidenceStore(driver).recent().single()
            assertTrue(auditEvent.metadata.isEmpty())
            assertFalse(auditEvent.message.contains("not-json"))
        }
    }

    @Test
    fun secretsAreRedactedBeforeDatabasePersistence() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            AppDatabase.Schema.awaitCreate(driver)
            val cache = SqlDelightSemanticCache(driver, nowEpochMs = { 10 })
            val audit = SqlDelightAuditEvidenceStore(driver)

            cache.put(
                SemanticCacheRecord(
                    id = "secret-fixture",
                    sourceUrl = "https://example.invalid/page?token=private-token",
                    title = "token=abcdef123456",
                    summary = "password: hunter2-secret",
                    content = "card 4111 1111 1111 1111",
                    createdAtEpochMs = 1,
                ),
            )
            audit.append(
                AuditEvent(
                    atEpochMs = 2,
                    category = "secret",
                    message = "authorization=private-token-value",
                    metadata = mapOf("cookie" to "session-secret"),
                ),
            )

            val persisted = SqlDelightSemanticCache(driver, nowEpochMs = { 11 })
                .query("REDACTED", 1)
                .single()
                .record
            assertEquals("https://example.invalid/page", persisted.sourceUrl)
            assertFalse(persisted.title.contains("abcdef123456"))
            assertFalse(persisted.summary.contains("hunter2-secret"))
            assertFalse(persisted.content.contains("4111"))

            val persistedAudit = SqlDelightAuditEvidenceStore(driver).recent().single()
            assertFalse(persistedAudit.message.contains("private-token-value"))
            assertEquals("[REDACTED]", persistedAudit.metadata.getValue("cookie"))
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
