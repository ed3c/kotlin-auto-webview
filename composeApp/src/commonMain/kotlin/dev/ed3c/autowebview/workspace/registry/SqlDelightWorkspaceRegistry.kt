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

    suspend fun putSubject(subject: SubjectRef, updatedAtEpochMs: Long) {
        require(updatedAtEpochMs >= 0) { "Subject update time cannot be negative" }
        queries.upsertWorkspaceSubject(
            logical_id = subject.key.logicalId,
            kind = subject.key.kind.name,
            payload_json = json.encodeToString(subject),
            updated_at_epoch_ms = updatedAtEpochMs,
        )
    }

    suspend fun subject(key: SubjectKey): SubjectRef? =
        queries.selectWorkspaceSubjectPayload(
            logical_id = key.logicalId,
            kind = key.kind.name,
        ) { payloadJson -> payloadJson }
            .awaitAsList()
            .singleOrNull()
            ?.let(::decodeSubjectOrNull)

    suspend fun activeSubjects(limit: Int = 100): List<SubjectRef> {
        if (limit <= 0) return emptyList()
        return queries.selectActiveWorkspaceSubjectPayloads(limit.toLong()) { payloadJson -> payloadJson }
            .awaitAsList()
            .mapNotNull(::decodeSubjectOrNull)
    }

    suspend fun tombstoneSubject(key: SubjectKey, updatedAtEpochMs: Long) {
        require(updatedAtEpochMs >= 0) { "Tombstone time cannot be negative" }
        queries.tombstoneWorkspaceSubject(
            updated_at_epoch_ms = updatedAtEpochMs,
            logical_id = key.logicalId,
            kind = key.kind.name,
        )
    }

    suspend fun activeSubjectCount(): Long = queries.countActiveWorkspaceSubjects().awaitAsOne()

    suspend fun putEdge(edge: TypedEdge, updatedAtEpochMs: Long) {
        require(updatedAtEpochMs >= 0) { "Edge update time cannot be negative" }
        queries.upsertWorkspaceEdge(
            edge_id = edge.edgeId,
            from_logical_id = edge.from.logicalId,
            from_kind = edge.from.kind.name,
            relation = edge.relation.name,
            to_logical_id = edge.to.logicalId,
            to_kind = edge.to.kind.name,
            payload_json = json.encodeToString(edge),
            updated_at_epoch_ms = updatedAtEpochMs,
        )
    }

    suspend fun edgesFrom(key: SubjectKey): List<TypedEdge> =
        queries.selectWorkspaceEdgePayloadsFrom(
            from_logical_id = key.logicalId,
            from_kind = key.kind.name,
        ) { payloadJson -> payloadJson }
            .awaitAsList()
            .mapNotNull(::decodeEdgeOrNull)

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

        return outboxEventIdByDedupe(dedupeKey) == receipt.eventId
    }

    suspend fun dispatchableSyncReceipts(nowEpochMs: Long, limit: Int = 32): List<SyncReceipt> {
        require(nowEpochMs >= 0) { "Dispatch time cannot be negative" }
        if (limit <= 0) return emptyList()
        return queries.selectDispatchableWorkspaceOutboxPayloads(
            next_attempt_at_epoch_ms = nowEpochMs,
            limit = limit.toLong(),
        ) { payloadJson -> payloadJson }
            .awaitAsList()
            .mapNotNull(::decodeSyncOrNull)
    }

    suspend fun markWriteSent(eventId: String, updatedAtEpochMs: Long): SyncReceipt {
        require(updatedAtEpochMs >= 0) { "Dispatch update time cannot be negative" }
        val current = outboxStateAndReceipt(eventId)
            ?: error("Outbox event does not exist: $eventId")
        val next = current.receipt.copy(
            state = SyncState.WRITE_SENT,
            attempts = current.receipt.attempts + 1,
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
        val current = outboxStateAndReceipt(receipt.eventId)
            ?: error("Outbox event does not exist: ${receipt.eventId}")
        require(WorkspaceSyncTransitions.allows(current.state, receipt.state)) {
            "Invalid sync transition ${current.state} -> ${receipt.state}"
        }
        require(receipt.attempts >= current.receipt.attempts) {
            "Sync attempt count cannot decrease"
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
        return inboxContains(proposal.proposalId)
    }

    suspend fun proposedChanges(limit: Int = 100): List<ChangeProposal> {
        if (limit <= 0) return emptyList()
        return queries.selectProposedWorkspaceInboxPayloads(limit.toLong()) { payloadJson -> payloadJson }
            .awaitAsList()
            .mapNotNull(::decodeProposalOrNull)
    }

    suspend fun recordChangeProposalDecision(proposal: ChangeProposal, updatedAtEpochMs: Long) {
        require(proposal.state != ChangeProposalState.PROPOSED) {
            "Decision update must leave PROPOSED state"
        }
        require(proposal.reviewer != null) { "Decision update requires a reviewer" }
        require(updatedAtEpochMs >= 0) { "Decision update time cannot be negative" }
        require(inboxContains(proposal.proposalId)) {
            "Inbox proposal does not exist: ${proposal.proposalId}"
        }
        queries.updateWorkspaceInboxChange(
            payload_json = json.encodeToString(proposal),
            state = proposal.state.name,
            updated_at_epoch_ms = updatedAtEpochMs,
            proposal_id = proposal.proposalId,
        )
    }

    suspend fun inboxCount(): Long = queries.countWorkspaceInbox().awaitAsOne()

    private suspend fun outboxEventIdByDedupe(dedupeKey: String): String? =
        queries.selectWorkspaceOutboxEventIdByDedupe(dedupe_key = dedupeKey) { eventId -> eventId }
            .awaitAsList()
            .singleOrNull()

    private suspend fun inboxContains(proposalId: String): Boolean =
        queries.selectWorkspaceInboxProposalId(proposal_id = proposalId) { id -> id }
            .awaitAsList()
            .singleOrNull() != null

    private suspend fun outboxStateAndReceipt(eventId: String): StoredSyncState? =
        queries.selectWorkspaceOutboxStateAndPayload(event_id = eventId) { state, payloadJson ->
            state to payloadJson
        }
            .awaitAsList()
            .singleOrNull()
            ?.let { (state, payload) ->
                val parsedState = runCatching { SyncState.valueOf(state) }.getOrNull() ?: return@let null
                val receipt = decodeSyncOrNull(payload) ?: return@let null
                StoredSyncState(parsedState, receipt)
            }

    private fun persistOutboxReceipt(
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
    }

    private fun decodeSubjectOrNull(payload: String): SubjectRef? =
        runCatching { json.decodeFromString<SubjectRef>(payload) }.getOrNull()

    private fun decodeEdgeOrNull(payload: String): TypedEdge? =
        runCatching { json.decodeFromString<TypedEdge>(payload) }.getOrNull()

    private fun decodeSyncOrNull(payload: String): SyncReceipt? =
        runCatching { json.decodeFromString<SyncReceipt>(payload) }.getOrNull()

    private fun decodeProposalOrNull(payload: String): ChangeProposal? =
        runCatching { json.decodeFromString<ChangeProposal>(payload) }.getOrNull()

    private data class StoredSyncState(
        val state: SyncState,
        val receipt: SyncReceipt,
    )
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
