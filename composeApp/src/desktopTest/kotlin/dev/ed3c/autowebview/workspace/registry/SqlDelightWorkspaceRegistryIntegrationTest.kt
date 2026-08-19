package dev.ed3c.autowebview.workspace.registry

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.async.coroutines.awaitMigrate
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ed3c.autowebview.persistence.db.AppDatabase
import dev.ed3c.autowebview.workspace.contract.AuthorityKind
import dev.ed3c.autowebview.workspace.contract.AuthorityRef
import dev.ed3c.autowebview.workspace.contract.ChangeProposal
import dev.ed3c.autowebview.workspace.contract.ChangeProposalState
import dev.ed3c.autowebview.workspace.contract.ConfidenceLevel
import dev.ed3c.autowebview.workspace.contract.DigestRef
import dev.ed3c.autowebview.workspace.contract.EdgeRelation
import dev.ed3c.autowebview.workspace.contract.EvidenceClass
import dev.ed3c.autowebview.workspace.contract.ExternalProvider
import dev.ed3c.autowebview.workspace.contract.ExternalRef
import dev.ed3c.autowebview.workspace.contract.FreshnessState
import dev.ed3c.autowebview.workspace.contract.SubjectDataClass
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectKind
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.SubjectVisibility
import dev.ed3c.autowebview.workspace.contract.SyncReceipt
import dev.ed3c.autowebview.workspace.contract.SyncState
import dev.ed3c.autowebview.workspace.contract.TypedEdge
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightWorkspaceRegistryIntegrationTest {
    private val github = AuthorityRef(AuthorityKind.GITHUB, "ed3c/kotlin-auto-webview")
    private val shaA = DigestRef(value = "a".repeat(64))

    @Test
    fun subjectEdgeOutboxAndInboxSurviveFileBackedReopen() = runTest {
        val path = Files.createTempFile("kaw-workspace-registry", ".db")
        val url = "jdbc:sqlite:${path.toAbsolutePath()}"
        val subject = privateSubject()
        val requirement = SubjectKey("REQ-LOCAL-1", SubjectKind.REQUIREMENT)
        val edge = TypedEdge(
            edgeId = "EDGE-LOCAL-1",
            from = subject.key,
            relation = EdgeRelation.SUPPORTS,
            to = requirement,
            owner = github,
            evidenceClass = EvidenceClass.SOURCE_OBSERVATION,
            confidence = ConfidenceLevel.HIGH,
        )
        val target = ExternalRef(
            provider = ExternalProvider.GITHUB,
            externalId = "issue-121",
            revision = "issue-revision-1",
            canonicalUrl = "https://github.com/ed3c/kotlin-auto-webview/issues/121",
            freshness = FreshnessState.CURRENT,
            observedAtEpochMs = 10,
        )
        val pending = SyncReceipt(
            eventId = "SYNC-LOCAL-1",
            canonicalSubject = subject.key,
            target = target,
            state = SyncState.PENDING,
            attempts = 0,
        )
        val proposal = ChangeProposal(
            proposalId = "CHANGE-LOCAL-1",
            canonicalSubject = subject.key,
            sourceProjectionId = "PROJ-LOCAL-1",
            proposer = AuthorityRef(AuthorityKind.EXTERNAL, "external-editor"),
            requestedChangeDigest = shaA,
        )

        try {
            JdbcSqliteDriver(url).use { driver ->
                AppDatabase.Schema.awaitCreate(driver)
                val registry = SqlDelightWorkspaceRegistry(driver)
                registry.putSubject(subject, updatedAtEpochMs = 10)
                registry.putEdge(edge, updatedAtEpochMs = 11)
                assertTrue(
                    registry.enqueueSync(
                        receipt = pending,
                        dedupeKey = "subject:${subject.key.logicalId}:projection:github",
                        nextAttemptAtEpochMs = 12,
                        createdAtEpochMs = 12,
                    ),
                )
                assertFalse(
                    registry.enqueueSync(
                        receipt = pending,
                        dedupeKey = "subject:${subject.key.logicalId}:projection:github",
                        nextAttemptAtEpochMs = 13,
                        createdAtEpochMs = 13,
                    ),
                )
                assertTrue(registry.enqueueChangeProposal(proposal, receivedAtEpochMs = 14))
                assertFalse(registry.enqueueChangeProposal(proposal, receivedAtEpochMs = 15))
            }

            JdbcSqliteDriver(url).use { reopenedDriver ->
                val registry = SqlDelightWorkspaceRegistry(reopenedDriver)
                assertEquals(subject, registry.subject(subject.key))
                assertEquals(listOf(edge), registry.edgesFrom(subject.key))
                assertEquals(1L, registry.activeSubjectCount())
                assertEquals(1L, registry.outboxCount())
                assertEquals(1L, registry.inboxCount())
                assertEquals(listOf(pending), registry.dispatchableSyncReceipts(nowEpochMs = 20))
                assertEquals(listOf(proposal), registry.proposedChanges())

                val sent = registry.markWriteSent(pending.eventId, updatedAtEpochMs = 21)
                assertEquals(SyncState.WRITE_SENT, sent.state)
                assertEquals(1, sent.attempts)

                val acknowledged = sent.copy(state = SyncState.WRITE_ACKNOWLEDGED)
                registry.recordSyncReceipt(
                    receipt = acknowledged,
                    nextAttemptAtEpochMs = 22,
                    updatedAtEpochMs = 22,
                )
                val verified = acknowledged.copy(
                    state = SyncState.READ_BACK_VERIFIED,
                    targetRevision = "issue-revision-2",
                    writtenDigest = shaA,
                    readBackDigest = shaA,
                )
                registry.recordSyncReceipt(
                    receipt = verified,
                    nextAttemptAtEpochMs = 23,
                    updatedAtEpochMs = 23,
                )
                assertEquals(verified, registry.outboxReceipt(pending.eventId))
                assertTrue(registry.dispatchableSyncReceipts(nowEpochMs = 100).isEmpty())

                val accepted = proposal.copy(
                    state = ChangeProposalState.ACCEPTED_FOR_CANONICAL_REVIEW,
                    reviewer = github,
                )
                registry.recordChangeProposalDecision(accepted, updatedAtEpochMs = 24)
                assertTrue(registry.proposedChanges().isEmpty())

                registry.tombstoneSubject(subject.key, updatedAtEpochMs = 25)
                assertEquals(0L, registry.activeSubjectCount())
                assertTrue(registry.activeSubjects().isEmpty())
            }

            JdbcSqliteDriver(url).use { secondReopen ->
                val registry = SqlDelightWorkspaceRegistry(secondReopen)
                assertEquals(1L, registry.outboxCount())
                assertEquals(SyncState.READ_BACK_VERIFIED, registry.outboxReceipt(pending.eventId)?.state)
                assertEquals(1L, registry.inboxCount())
                assertEquals(0L, registry.activeSubjectCount())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun migrationFromVersionTwoAddsWorkspaceTablesWithoutReplacingExistingSchema() = runTest {
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
                      last_accessed_at_epoch_ms INTEGER NOT NULL,
                      tags_json TEXT NOT NULL
                    )
                """.trimIndent(),
                parameters = 0,
            )
            driver.await(
                identifier = null,
                sql = """
                    CREATE INDEX semantic_cache_created_idx
                    ON semantic_cache_record(
                      last_accessed_at_epoch_ms DESC,
                      created_at_epoch_ms DESC,
                      id ASC
                    )
                """.trimIndent(),
                parameters = 0,
            )
            driver.await(
                identifier = null,
                sql = """
                    CREATE TABLE audit_event (
                      sequence INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                      at_epoch_ms INTEGER NOT NULL,
                      category TEXT NOT NULL,
                      message TEXT NOT NULL,
                      metadata_json TEXT NOT NULL
                    )
                """.trimIndent(),
                parameters = 0,
            )
            driver.await(
                identifier = null,
                sql = """
                    CREATE INDEX audit_event_time_idx
                    ON audit_event(at_epoch_ms DESC, sequence DESC)
                """.trimIndent(),
                parameters = 0,
            )

            AppDatabase.Schema.awaitMigrate(driver, oldVersion = 2, newVersion = 3)

            val registry = SqlDelightWorkspaceRegistry(driver)
            val subject = privateSubject().copy(
                key = SubjectKey("SRC-MIGRATED-1", SubjectKind.SOURCE),
            )
            registry.putSubject(subject, updatedAtEpochMs = 30)
            assertEquals(subject, registry.subject(subject.key))
            assertEquals(1L, registry.activeSubjectCount())
        }
    }

    @Test
    fun corruptSubjectPayloadFailsClosedInsteadOfCrashing() = runTest {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            AppDatabase.Schema.awaitCreate(driver)
            driver.await(
                identifier = null,
                sql = """
                    INSERT INTO workspace_subject_projection(
                      logical_id, kind, payload_json, updated_at_epoch_ms, tombstoned
                    ) VALUES ('SRC-CORRUPT-1', 'SOURCE', '{not-json', 1, 0)
                """.trimIndent(),
                parameters = 0,
            )

            val registry = SqlDelightWorkspaceRegistry(driver)
            val key = SubjectKey("SRC-CORRUPT-1", SubjectKind.SOURCE)
            assertNull(registry.subject(key))
            assertTrue(registry.activeSubjects().isEmpty())
            assertEquals(1L, registry.activeSubjectCount())
        }
    }

    private fun privateSubject() = SubjectRef(
        key = SubjectKey("SRC-LOCAL-1", SubjectKind.SOURCE),
        canonicalAuthority = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "fixture/private-domain"),
        version = "fixture-v1",
        digest = shaA,
        visibility = SubjectVisibility.PRIVATE,
        dataClass = SubjectDataClass.CONFIDENTIAL,
    )
}
