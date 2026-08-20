package dev.ed3c.autowebview.workspace.registry

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.SqlDriver
import dev.ed3c.autowebview.persistence.db.AppDatabase
import dev.ed3c.autowebview.workspace.contract.ChangeProposal
import dev.ed3c.autowebview.workspace.contract.ChangeProposalState
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.SyncReceipt
import dev.ed3c.autowebview.workspace.contract.SyncState
import dev.ed3c.autowebview.workspace.contract.TypedEdge
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SqlDelightWorkspaceRegistry(
    driver: SqlDriver,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) {
    private val database = AppDatabase(driver)
    private val queries = database.workspaceRegistryQueries

    suspend fun putSubject(subject: SubjectRef, updatedAtEpochMs: Long): Boolean {
        require(updatedAtEpochMs >= 0) { "Subject update time cannot be negative" }
        val payload = json.encodeToString(subject)
        database.transaction {
            queries.updateWorkspaceSubjectIfFresh(
                payload_json = payload,
                updated_at_epoch_ms = updatedAtEpochMs,
                logical_id = subject.key.logicalId,
                kind = subject.key.kind.name,
            )
            queries.insertWorkspaceSubjectIfAbsent(
                logical_id = subject.key.logicalId,
                kind = subject.key.kind.name,
                payload_json = payload,
                updated_at_epoch_ms = updatedAtEpochMs,
            )
        }
        val stored = subjectRow(subject.key)
        return stored != null &&
            stored.subject == subject &&
            stored.updatedAtEpochMs == updatedAtEpochMs &&
            !stored.tombstoned
    }

    suspend fun subject(key: SubjectKey): SubjectRef? = subjectRow(key)?.subject

    suspend fun activeSubjects(limit: Int = 100): List<SubjectRef> {
        if (limit <= 0) return emptyList()
        return queries.selectActiveWorkspaceSubjectRows(limit.toLong()) {
                logicalId,
                kind,
                payloadJson,
                updatedAtEpochMs,
                tombstoned,
            ->
            RawSubjectRow(
                logicalId = logicalId,
                kind = kind,
                payloadJson = payloadJson,
                updatedAtEpochMs = updatedAtEpochMs,
                tombstoned = tombstoned,
            )
        }
            .awaitAsList()
            .mapNotNull(::decodeSubjectRow)
            .filter { stored -> !stored.tombstoned }
            .map(StoredSubject::subject)
    }

    suspend fun tombstoneSubject(key: SubjectKey, updatedAtEpochMs: Long): Boolean {
        require(updatedAtEpochMs >= 0) { "Tombstone time cannot be negative" }
        queries.tombstoneWorkspaceSubject(
            updated_at_epoch_ms = updatedAtEpochMs,
            logical_id = key.logicalId,
            kind = key.kind.name,
        )
        val stored = subjectRow(key)
        return stored != null &&
            stored.tombstoned &&
            stored.updatedAtEpochMs == updatedAtEpochMs
    }

    suspend fun activeSubjectCount(): Long = queries.countActiveWorkspaceSubjects().awaitAsOne()

    suspend fun putEdge(edge: TypedEdge, updatedAtEpochMs: Long): Boolean {
        require(updatedAtEpochMs >= 0) { "Edge update time cannot be negative" }
        val payload = json.encodeToString(edge)
        database.transaction {
            queries.updateWorkspaceEdgeIfFresh(
                payload_json = payload,
                updated_at_epoch_ms = updatedAtEpochMs,
                edge_id = edge.edgeId,
                from_logical_id = edge.from.logicalId,
                from_kind = edge.from.kind.name,
                relation = edge.relation.name,
                to_logical_id = edge.to.logicalId,
                to_kind = edge.to.kind.name,
            )
            queries.insertWorkspaceEdgeIfAbsent(
                edge_id = edge.edgeId,
                from_logical_id = edge.from.logicalId,
                from_kind = edge.from.kind.name,
                relation = edge.relation.name,
                to_logical_id = edge.to.logicalId,
                to_kind = edge.to.kind.name,
                payload_json = payload,
                updated_at_epoch_ms = updatedAtEpochMs,
            )
        }
        val stored = edgeRow(edge.edgeId)
        return stored != null &&
            stored.edge == edge &&
            stored.updatedAtEpochMs == updatedAtEpochMs
    }

    suspend fun edgesFrom(key: SubjectKey): List<TypedEdge> =
        queries.selectWorkspaceEdgeRowsFrom(
            from_logical_id = key.logicalId,
            from_kind = key.kind.name,
        ) {
                edgeId,
                fromLogicalId,
                fromKind,
                relation,
                toLogicalId,
                toKind,
                payloadJson,
                updatedAtEpochMs,
            ->
            RawEdgeRow(
                edgeId = edgeId,
                fromLogicalId = fromLogicalId,
                fromKind = fromKind,
                relation = relation,
                toLogicalId = toLogicalId,
                toKind = toKind,
                payloadJson = payloadJson,
                updatedAtEpochMs = updatedAtEpochMs,
            )
        }
            .awaitAsList()
            .mapNotNull(::decodeEdgeRow)
            .filter { stored -> stored.edge.from == key }
            .map(StoredEdge::edge)

    suspend fun enqueueSync(
        receipt: SyncReceipt,
        dedupeKey: String,
        nextAttemptAtEpochMs: Long,
        createdAtEpochMs: Long,
    ): Boolean {
        require(receipt.state == SyncState.PENDING) { "New outbox receipts must start PENDING" }
        require(receipt.attempts == 0) { "New outbox receipts cannot have prior attempts" }
        require(dedupeKey.isNotBlank()) { "Outbox dedupe key cannot be blank" }
        require(dedupeKey.length <= 512) { "Outbox dedupe key is too long" }
        require(nextAttemptAtEpochMs >= 0) { "Next-attempt time cannot be negative" }
        require(createdAtEpochMs >= 0) { "Created time cannot be negative" }
        require(
            receipt.targetRevision == null &&
                receipt.writtenDigest == null &&
                receipt.readBackDigest == null &&
                receipt.errorCode == null,
        ) {
            "New outbox receipts cannot carry pre-existing write or read-back evidence"
        }

        val existing = outboxEventIdByDedupe(dedupeKey)
        if (existing != null) return false

        queries.enqueueWorkspaceOutbox(
            event_id = receipt.eventId,
            canonical_logical_id = receipt.canonicalSubject.logicalId,
            canonical_kind = receipt.canonicalSubject.kind.name,
            payload_json = json.encodeToString(receipt),
            state = receipt.state.name,
            attempt_count = receipt.attempts.toLong(),
            dedupe_key = dedupeKey,
            next_attempt_at_epoch_ms = nextAttemptAtEpochMs,
            created_at_epoch_ms = createdAtEpochMs,
            updated_at_epoch_ms = createdAtEpochMs,
            last_error_code = receipt.errorCode,
        )

        val stored = outboxStateAndReceipt(receipt.eventId)
        return stored != null &&
            outboxEventIdByDedupe(dedupeKey) == receipt.eventId &&
            stored.state == SyncState.PENDING &&
            stored.receipt == receipt
    }

    suspend fun dispatchableSyncReceipts(nowEpochMs: Long, limit: Int = 32): List<SyncReceipt> {
        require(nowEpochMs >= 0) { "Dispatch time cannot be negative" }
        if (limit <= 0) return emptyList()
        return queries.selectDispatchableWorkspaceOutboxPayloads(
            nowEpochMs,
            limit.toLong(),
        ) {
                eventId,
                canonicalLogicalId,
                canonicalKind,
                state,
                attemptCount,
                payloadJson,
            ->
            RawSyncRow(
                eventId = eventId,
                canonicalLogicalId = canonicalLogicalId,
                canonicalKind = canonicalKind,
                state = state,
                attemptCount = attemptCount,
                payloadJson = payloadJson,
            )
        }
            .awaitAsList()
            .mapNotNull(::decodeSyncRow)
            .filter { stored -> stored.state in DISPATCHABLE_SYNC_STATES }
            .map(StoredSyncState::receipt)
    }

    suspend fun markWriteSent(eventId: String, updatedAtEpochMs: Long): SyncReceipt {
        require(updatedAtEpochMs >= 0) { "Dispatch update time cannot be negative" }
        val current = outboxStateAndReceipt(eventId)
            ?: error("Outbox event does not exist or is corrupt: $eventId")
        val next = current.receipt.copy(
            state = SyncState.WRITE_SENT,
            attempts = current.receipt.attempts + 1,
            targetRevision = null,
            writtenDigest = null,
            readBackDigest = null,
            errorCode = null,
        )
        require(WorkspaceSyncTransitions.allows(current.state, next.state)) {
            "Invalid sync transition ${current.state} -> ${next.state}"
        }
        persistOutboxReceipt(next, updatedAtEpochMs, updatedAtEpochMs)
        return next
    }

    suspend fun recordSyncReceipt(
        receipt: SyncReceipt,
        nextAttemptAtEpochMs: Long,
        updatedAtEpochMs: Long,
    ) {
        require(nextAttemptAtEpochMs >= 0) { "Next-attempt time cannot be negative" }
        require(updatedAtEpochMs >= 0) { "Receipt update time cannot be negative" }
        require(receipt.state != SyncState.WRITE_SENT) {
            "WRITE_SENT transitions must use markWriteSent so attempts increment exactly once"
        }
        val current = outboxStateAndReceipt(receipt.eventId)
            ?: error("Outbox event does not exist or is corrupt: ${receipt.eventId}")
        require(receipt.canonicalSubject == current.receipt.canonicalSubject) {
            "Sync canonical subject cannot change during a state transition"
        }
        require(receipt.target == current.receipt.target) {
            "Sync target cannot change during a state transition"
        }
        require(WorkspaceSyncTransitions.allows(current.state, receipt.state)) {
            "Invalid sync transition ${current.state} -> ${receipt.state}"
        }
        require(receipt.attempts == current.receipt.attempts) {
            "Sync attempt count changes only when markWriteSent dispatches an attempt"
        }
        persistOutboxReceipt(receipt, nextAttemptAtEpochMs, updatedAtEpochMs)
    }

    suspend fun outboxReceipt(eventId: String): SyncReceipt? = outboxStateAndReceipt(eventId)?.receipt

    suspend fun outboxCount(): Long = queries.countWorkspaceOutbox().awaitAsOne()

    suspend fun enqueueChangeProposal(proposal: ChangeProposal, receivedAtEpochMs: Long): Boolean {
        require(proposal.state == ChangeProposalState.PROPOSED) {
            "Inbox changes must enter as PROPOSED"
        }
        require(receivedAtEpochMs >= 0) { "Inbox receive time cannot be negative" }
        require(proposal.reviewer == null) { "New inbox proposals cannot be pre-reviewed" }
        if (inboxContains(proposal.proposalId)) return false

        queries.insertWorkspaceInboxChange(
            proposal_id = proposal.proposalId,
            canonical_logical_id = proposal.canonicalSubject.logicalId,
            canonical_kind = proposal.canonicalSubject.kind.name,
            source_projection_id = proposal.sourceProjectionId,
            payload_json = json.encodeToString(proposal),
            state = proposal.state.name,
            received_at_epoch_ms = receivedAtEpochMs,
            updated_at_epoch_ms = receivedAtEpochMs,
        )
        return inboxProposal(proposal.proposalId) == proposal
    }

    suspend fun proposedChanges(limit: Int = 100): List<ChangeProposal> {
        if (limit <= 0) return emptyList()
        return queries.selectProposedWorkspaceInboxPayloads(limit.toLong()) {
                proposalId,
                canonicalLogicalId,
                canonicalKind,
                sourceProjectionId,
                state,
                payloadJson,
            ->
            RawProposalRow(
                proposalId = proposalId,
                canonicalLogicalId = canonicalLogicalId,
                canonicalKind = canonicalKind,
                sourceProjectionId = sourceProjectionId,
                state = state,
                payloadJson = payloadJson,
            )
        }
            .awaitAsList()
            .mapNotNull(::decodeProposalRow)
            .filter { proposal -> proposal.state == ChangeProposalState.PROPOSED }
    }

    suspend fun recordChangeProposalDecision(proposal: ChangeProposal, updatedAtEpochMs: Long) {
        require(proposal.state != ChangeProposalState.PROPOSED) {
            "Decision update must leave PROPOSED state"
        }
        require(proposal.reviewer != null) { "Decision update requires a reviewer" }
        require(updatedAtEpochMs >= 0) { "Decision update time cannot be negative" }

        val current = inboxProposal(proposal.proposalId)
            ?: error("Inbox proposal does not exist or is corrupt: ${proposal.proposalId}")
        require(current.state == ChangeProposalState.PROPOSED) {
            "A reviewed proposal is terminal and cannot be decided again"
        }
        require(proposal.canonicalSubject == current.canonicalSubject) {
            "Proposal canonical subject cannot change during review"
        }
        require(proposal.sourceProjectionId == current.sourceProjectionId) {
            "Proposal source projection cannot change during review"
        }
        require(proposal.proposer == current.proposer) {
            "Proposal proposer cannot change during review"
        }
        require(proposal.requestedChangeDigest == current.requestedChangeDigest) {
            "Proposal requested change cannot change during review"
        }

        queries.updateWorkspaceInboxChange(
            payload_json = json.encodeToString(proposal),
            state = proposal.state.name,
            updated_at_epoch_ms = updatedAtEpochMs,
            proposal_id = proposal.proposalId,
        )
        check(inboxProposal(proposal.proposalId) == proposal) {
            "Proposal decision did not survive local read-back"
        }
    }

    suspend fun inboxCount(): Long = queries.countWorkspaceInbox().awaitAsOne()

    private suspend fun subjectRow(key: SubjectKey): StoredSubject? =
        queries.selectWorkspaceSubjectRow(
            logical_id = key.logicalId,
            kind = key.kind.name,
        ) {
                logicalId,
                kind,
                payloadJson,
                updatedAtEpochMs,
                tombstoned,
            ->
            RawSubjectRow(
                logicalId = logicalId,
                kind = kind,
                payloadJson = payloadJson,
                updatedAtEpochMs = updatedAtEpochMs,
                tombstoned = tombstoned,
            )
        }
            .awaitAsList()
            .singleOrNull()
            ?.let(::decodeSubjectRow)

    private suspend fun edgeRow(edgeId: String): StoredEdge? =
        queries.selectWorkspaceEdgeRow(edge_id = edgeId) {
                storedEdgeId,
                fromLogicalId,
                fromKind,
                relation,
                toLogicalId,
                toKind,
                payloadJson,
                updatedAtEpochMs,
            ->
            RawEdgeRow(
                edgeId = storedEdgeId,
                fromLogicalId = fromLogicalId,
                fromKind = fromKind,
                relation = relation,
                toLogicalId = toLogicalId,
                toKind = toKind,
                payloadJson = payloadJson,
                updatedAtEpochMs = updatedAtEpochMs,
            )
        }
            .awaitAsList()
            .singleOrNull()
            ?.let(::decodeEdgeRow)

    private suspend fun outboxEventIdByDedupe(dedupeKey: String): String? =
        queries.selectWorkspaceOutboxEventIdByDedupe(dedupe_key = dedupeKey)
            .awaitAsList()
            .singleOrNull()

    private suspend fun inboxContains(proposalId: String): Boolean =
        queries.selectWorkspaceInboxProposalId(proposal_id = proposalId)
            .awaitAsList()
            .singleOrNull() != null

    private suspend fun inboxProposal(proposalId: String): ChangeProposal? =
        queries.selectWorkspaceInboxStateAndPayload(proposal_id = proposalId) {
                storedProposalId,
                canonicalLogicalId,
                canonicalKind,
                sourceProjectionId,
                state,
                payloadJson,
            ->
            RawProposalRow(
                proposalId = storedProposalId,
                canonicalLogicalId = canonicalLogicalId,
                canonicalKind = canonicalKind,
                sourceProjectionId = sourceProjectionId,
                state = state,
                payloadJson = payloadJson,
            )
        }
            .awaitAsList()
            .singleOrNull()
            ?.let(::decodeProposalRow)

    private suspend fun outboxStateAndReceipt(eventId: String): StoredSyncState? =
        queries.selectWorkspaceOutboxStateAndPayload(event_id = eventId) {
                storedEventId,
                canonicalLogicalId,
                canonicalKind,
                state,
                attemptCount,
                payloadJson,
            ->
            RawSyncRow(
                eventId = storedEventId,
                canonicalLogicalId = canonicalLogicalId,
                canonicalKind = canonicalKind,
                state = state,
                attemptCount = attemptCount,
                payloadJson = payloadJson,
            )
        }
            .awaitAsList()
            .singleOrNull()
            ?.let(::decodeSyncRow)

    private suspend fun persistOutboxReceipt(
        receipt: SyncReceipt,
        nextAttemptAtEpochMs: Long,
        updatedAtEpochMs: Long,
    ) {
        queries.updateWorkspaceOutbox(
            payload_json = json.encodeToString(receipt),
            state = receipt.state.name,
            attempt_count = receipt.attempts.toLong(),
            next_attempt_at_epoch_ms = nextAttemptAtEpochMs,
            updated_at_epoch_ms = updatedAtEpochMs,
            last_error_code = receipt.errorCode,
            event_id = receipt.eventId,
        )
        val persisted = outboxStateAndReceipt(receipt.eventId)
        check(
            persisted != null &&
                persisted.state == receipt.state &&
                persisted.receipt == receipt,
        ) {
            "Sync receipt did not survive local read-back"
        }
    }

    private fun decodeSubjectRow(row: RawSubjectRow): StoredSubject? {
        if (row.tombstoned !in 0L..1L) return null
        val subject = runCatching { json.decodeFromString<SubjectRef>(row.payloadJson) }.getOrNull()
            ?: return null
        if (subject.key.logicalId != row.logicalId || subject.key.kind.name != row.kind) return null
        return StoredSubject(
            subject = subject,
            updatedAtEpochMs = row.updatedAtEpochMs,
            tombstoned = row.tombstoned == 1L,
        )
    }

    private fun decodeEdgeRow(row: RawEdgeRow): StoredEdge? {
        val edge = runCatching { json.decodeFromString<TypedEdge>(row.payloadJson) }.getOrNull()
            ?: return null
        if (edge.edgeId != row.edgeId) return null
        if (edge.from.logicalId != row.fromLogicalId || edge.from.kind.name != row.fromKind) return null
        if (edge.relation.name != row.relation) return null
        if (edge.to.logicalId != row.toLogicalId || edge.to.kind.name != row.toKind) return null
        return StoredEdge(edge = edge, updatedAtEpochMs = row.updatedAtEpochMs)
    }

    private fun decodeSyncRow(row: RawSyncRow): StoredSyncState? {
        val state = runCatching { SyncState.valueOf(row.state) }.getOrNull() ?: return null
        val receipt = runCatching { json.decodeFromString<SyncReceipt>(row.payloadJson) }.getOrNull()
            ?: return null
        if (receipt.eventId != row.eventId) return null
        if (receipt.canonicalSubject.logicalId != row.canonicalLogicalId) return null
        if (receipt.canonicalSubject.kind.name != row.canonicalKind) return null
        if (receipt.state != state) return null
        if (receipt.attempts.toLong() != row.attemptCount) return null
        return StoredSyncState(state = state, receipt = receipt)
    }

    private fun decodeProposalRow(row: RawProposalRow): ChangeProposal? {
        val state = runCatching { ChangeProposalState.valueOf(row.state) }.getOrNull() ?: return null
        val proposal = runCatching {
            json.decodeFromString<ChangeProposal>(row.payloadJson)
        }.getOrNull() ?: return null
        if (proposal.proposalId != row.proposalId) return null
        if (proposal.canonicalSubject.logicalId != row.canonicalLogicalId) return null
        if (proposal.canonicalSubject.kind.name != row.canonicalKind) return null
        if (proposal.sourceProjectionId != row.sourceProjectionId) return null
        if (proposal.state != state) return null
        if (proposal.state == ChangeProposalState.PROPOSED && proposal.reviewer != null) return null
        return proposal
    }

    private data class RawSubjectRow(
        val logicalId: String,
        val kind: String,
        val payloadJson: String,
        val updatedAtEpochMs: Long,
        val tombstoned: Long,
    )

    private data class StoredSubject(
        val subject: SubjectRef,
        val updatedAtEpochMs: Long,
        val tombstoned: Boolean,
    )

    private data class RawEdgeRow(
        val edgeId: String,
        val fromLogicalId: String,
        val fromKind: String,
        val relation: String,
        val toLogicalId: String,
        val toKind: String,
        val payloadJson: String,
        val updatedAtEpochMs: Long,
    )

    private data class StoredEdge(
        val edge: TypedEdge,
        val updatedAtEpochMs: Long,
    )

    private data class RawSyncRow(
        val eventId: String,
        val canonicalLogicalId: String,
        val canonicalKind: String,
        val state: String,
        val attemptCount: Long,
        val payloadJson: String,
    )

    private data class StoredSyncState(
        val state: SyncState,
        val receipt: SyncReceipt,
    )

    private data class RawProposalRow(
        val proposalId: String,
        val canonicalLogicalId: String,
        val canonicalKind: String,
        val sourceProjectionId: String,
        val state: String,
        val payloadJson: String,
    )

    private companion object {
        val DISPATCHABLE_SYNC_STATES = setOf(
            SyncState.PENDING,
            SyncState.RETRYABLE_FAILURE,
        )
    }
}

internal object WorkspaceSyncTransitions {
    private val terminal = setOf(
        SyncState.READ_BACK_VERIFIED,
        SyncState.CONFLICT,
        SyncState.FAILED,
        SyncState.CLEANED_UP,
    )

    fun allows(from: SyncState, to: SyncState): Boolean {
        if (from == to) return false
        if (from == SyncState.CLEANED_UP) return false
        if (to == SyncState.CLEANED_UP) return from in terminal - SyncState.CLEANED_UP
        return when (from) {
            SyncState.PENDING -> to in setOf(
                SyncState.WRITE_SENT,
                SyncState.RETRYABLE_FAILURE,
                SyncState.FAILED,
            )
            SyncState.WRITE_SENT -> to in setOf(
                SyncState.WRITE_ACKNOWLEDGED,
                SyncState.RETRYABLE_FAILURE,
                SyncState.FAILED,
            )
            SyncState.WRITE_ACKNOWLEDGED -> to in setOf(
                SyncState.READ_BACK_VERIFIED,
                SyncState.CONFLICT,
                SyncState.RETRYABLE_FAILURE,
                SyncState.FAILED,
            )
            SyncState.RETRYABLE_FAILURE -> to in setOf(
                SyncState.WRITE_SENT,
                SyncState.FAILED,
            )
            SyncState.READ_BACK_VERIFIED,
            SyncState.CONFLICT,
            SyncState.FAILED,
            SyncState.CLEANED_UP,
            -> false
        }
    }
}
