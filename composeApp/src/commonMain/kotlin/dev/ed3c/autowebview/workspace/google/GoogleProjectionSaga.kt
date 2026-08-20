package dev.ed3c.autowebview.workspace.google

import dev.ed3c.autowebview.workspace.contract.AuthorityKind
import dev.ed3c.autowebview.workspace.contract.AuthorityRef
import dev.ed3c.autowebview.workspace.contract.ChangeProposal
import dev.ed3c.autowebview.workspace.contract.ChangeProposalState
import dev.ed3c.autowebview.workspace.contract.ExternalProvider
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.SyncReceipt
import dev.ed3c.autowebview.workspace.contract.SyncState
import dev.ed3c.autowebview.workspace.registry.SqlDelightWorkspaceRegistry

private val GOOGLE_EVENT_ID_PATTERN = Regex("^GPEVT:([A-Za-z][A-Za-z0-9_-]{2,47}):([A-Za-z0-9_-]{3,48})$")

interface GoogleProjectionStore {
    suspend fun enqueue(
        receipt: SyncReceipt,
        dedupeKey: String,
        nextAttemptAtEpochMs: Long,
        createdAtEpochMs: Long,
    ): Boolean

    suspend fun dispatchable(nowEpochMs: Long, limit: Int): List<SyncReceipt>

    suspend fun markWriteSent(eventId: String, updatedAtEpochMs: Long): SyncReceipt

    suspend fun record(
        receipt: SyncReceipt,
        nextAttemptAtEpochMs: Long,
        updatedAtEpochMs: Long,
    )

    suspend fun enqueueChangeProposal(proposal: ChangeProposal, receivedAtEpochMs: Long): Boolean
}

class SqlDelightGoogleProjectionStore(
    private val registry: SqlDelightWorkspaceRegistry,
) : GoogleProjectionStore {
    override suspend fun enqueue(
        receipt: SyncReceipt,
        dedupeKey: String,
        nextAttemptAtEpochMs: Long,
        createdAtEpochMs: Long,
    ): Boolean = registry.enqueueSync(
        receipt = receipt,
        dedupeKey = dedupeKey,
        nextAttemptAtEpochMs = nextAttemptAtEpochMs,
        createdAtEpochMs = createdAtEpochMs,
    )

    override suspend fun dispatchable(nowEpochMs: Long, limit: Int): List<SyncReceipt> =
        registry.dispatchableSyncReceipts(nowEpochMs, limit)

    override suspend fun markWriteSent(eventId: String, updatedAtEpochMs: Long): SyncReceipt =
        registry.markWriteSent(eventId, updatedAtEpochMs)

    override suspend fun record(
        receipt: SyncReceipt,
        nextAttemptAtEpochMs: Long,
        updatedAtEpochMs: Long,
    ) = registry.recordSyncReceipt(receipt, nextAttemptAtEpochMs, updatedAtEpochMs)

    override suspend fun enqueueChangeProposal(
        proposal: ChangeProposal,
        receivedAtEpochMs: Long,
    ): Boolean = registry.enqueueChangeProposal(proposal, receivedAtEpochMs)
}

sealed interface GoogleProjectionEnqueueResult {
    data class Enqueued(val eventId: String) : GoogleProjectionEnqueueResult
    data class Duplicate(val eventId: String) : GoogleProjectionEnqueueResult
    data class Blocked(val reasonCode: String) : GoogleProjectionEnqueueResult
}

class GoogleProjectionSaga(
    private val store: GoogleProjectionStore,
    private val payloadSource: GoogleProjectionPayloadSource,
    private val transport: GoogleProjectionTransport,
    private val maxAttempts: Int = 3,
    private val retryDelayMs: Long = 1_000,
) {
    init {
        require(maxAttempts in 1..20) { "Google projection max attempts must be bounded" }
        require(retryDelayMs >= 0) { "Google projection retry delay cannot be negative" }
    }

    suspend fun enqueue(
        eventId: String,
        subject: SubjectRef,
        binding: GoogleProjectionBinding,
        nowEpochMs: Long,
    ): GoogleProjectionEnqueueResult {
        require(nowEpochMs >= 0) { "Google projection enqueue time cannot be negative" }
        val eventMatch = GOOGLE_EVENT_ID_PATTERN.matchEntire(eventId)
            ?: throw IllegalArgumentException("Google projection event id is invalid")
        require(eventMatch.groupValues[1] == binding.projectionId) {
            "Google projection event id must embed its projection id"
        }
        require(binding.canonicalSubject == subject.key) {
            "Google projection binding and canonical subject must match"
        }
        val canonicalDigest = subject.digest
            ?: return GoogleProjectionEnqueueResult.Blocked("CANONICAL_DIGEST_ABSENT")
        if (!binding.isDestinationAdmitted(subject)) {
            return GoogleProjectionEnqueueResult.Blocked(
                when (binding.destinationAdmission) {
                    GoogleDestinationAdmission.LOCAL_ONLY -> "DESTINATION_LOCAL_ONLY"
                    GoogleDestinationAdmission.EXTERNAL_AUTHORITY_REQUIRED ->
                        "DESTINATION_EXTERNAL_AUTHORITY_REQUIRED"
                    GoogleDestinationAdmission.ADMITTED -> "DESTINATION_NOT_ADMITTED"
                },
            )
        }

        val receipt = SyncReceipt(
            eventId = eventId,
            canonicalSubject = subject.key,
            target = binding.toTarget(nowEpochMs),
            state = SyncState.PENDING,
            attempts = 0,
        )
        val dedupeKey = buildString {
            append("google:")
            append(binding.projectionId)
            append(':')
            append(binding.kind.name)
            append(':')
            append(binding.fileId)
            append(':')
            append(canonicalDigest.value)
        }
        return if (
            store.enqueue(
                receipt = receipt,
                dedupeKey = dedupeKey,
                nextAttemptAtEpochMs = nowEpochMs,
                createdAtEpochMs = nowEpochMs,
            )
        ) {
            GoogleProjectionEnqueueResult.Enqueued(eventId)
        } else {
            GoogleProjectionEnqueueResult.Duplicate(eventId)
        }
    }

    suspend fun dispatchAvailable(
        nowEpochMs: Long,
        limit: Int = 16,
    ): List<GoogleProjectionDispatchResult> {
        require(nowEpochMs >= 0) { "Google projection dispatch time cannot be negative" }
        if (limit <= 0) return emptyList()
        return store.dispatchable(nowEpochMs, limit).map { receipt ->
            dispatchOne(receipt, nowEpochMs)
        }
    }

    suspend fun captureObservedManualChange(
        binding: GoogleProjectionBinding,
        canonicalPayload: GoogleProjectionPayload,
        remote: GoogleProjectionRemoteSnapshot,
        proposalId: String,
        observedAtEpochMs: Long,
    ): ChangeProposal? {
        require(observedAtEpochMs >= 0) { "Google manual-change observation time cannot be negative" }
        if (binding.canonicalSubject != canonicalPayload.subject.key) return null
        if (remote.fileId != binding.fileId) return null
        if (remote.canonicalSubject != canonicalPayload.subject.key) return null
        if (
            remote.canonicalDigest == canonicalPayload.subject.digest &&
            remote.renderedDigest == canonicalPayload.renderedDigest
        ) {
            return null
        }
        val proposal = ChangeProposal(
            proposalId = proposalId,
            canonicalSubject = canonicalPayload.subject.key,
            sourceProjectionId = binding.projectionId,
            proposer = GOOGLE_CHANGE_PROPOSER,
            requestedChangeDigest = remote.renderedDigest,
            state = ChangeProposalState.PROPOSED,
        )
        return if (store.enqueueChangeProposal(proposal, observedAtEpochMs)) proposal else null
    }

    private suspend fun dispatchOne(
        pending: SyncReceipt,
        nowEpochMs: Long,
    ): GoogleProjectionDispatchResult {
        val kind = runCatching { pending.target.provider.googleProjectionKind() }.getOrNull()
            ?: return blockWithoutWrite(pending, "TARGET_PROVIDER_NOT_GOOGLE_PROJECTION", nowEpochMs)
        val binding = bindingFrom(pending, kind)
            ?: return blockWithoutWrite(pending, "TARGET_BINDING_INVALID", nowEpochMs)
        val payload = payloadSource.render(pending.canonicalSubject, kind)
            ?: return retryWithoutWrite(pending, "CANONICAL_PAYLOAD_UNAVAILABLE", nowEpochMs)
        if (payload.subject.key != pending.canonicalSubject || payload.subject.digest == null) {
            return blockWithoutWrite(pending, "CANONICAL_PAYLOAD_IDENTITY_MISMATCH", nowEpochMs)
        }
        if (payload.destinationAdmission != GoogleDestinationAdmission.ADMITTED) {
            return blockWithoutWrite(pending, "DESTINATION_NO_LONGER_ADMITTED", nowEpochMs)
        }

        val preRead = when (val result = transport.read(binding)) {
            is GoogleProjectionReadResult.Blocked ->
                return blockWithoutWrite(pending, result.reasonCode, nowEpochMs)
            is GoogleProjectionReadResult.RetryableFailure ->
                return retryWithoutWrite(pending, result.reasonCode, nowEpochMs)
            is GoogleProjectionReadResult.Found -> result.snapshot
        }
        if (preRead.fileId != binding.fileId) {
            return blockWithoutWrite(pending, "TARGET_FILE_ID_MISMATCH", nowEpochMs)
        }
        if (
            preRead.canonicalSubject != null &&
            preRead.canonicalSubject != pending.canonicalSubject
        ) {
            return blockWithoutWrite(pending, "TARGET_CANONICAL_SUBJECT_MISMATCH", nowEpochMs)
        }

        if (remoteMatches(preRead, payload)) {
            return recordAlreadyCurrent(pending, kind, preRead, payload, nowEpochMs)
        }

        if (
            binding.expectedRevision != null &&
            preRead.revision != binding.expectedRevision
        ) {
            val proposal = captureRevisionDrift(pending, binding, payload, preRead, nowEpochMs)
            return blockWithoutWrite(
                pending = pending,
                reasonCode = "TARGET_REVISION_CHANGED",
                nowEpochMs = nowEpochMs,
                proposal = proposal,
            )
        }

        val sent = store.markWriteSent(pending.eventId, nowEpochMs)
        val command = GoogleProjectionWriteCommand(
            eventId = sent.eventId,
            binding = binding.copy(expectedRevision = preRead.revision),
            payload = payload,
            ifRevisionMatches = preRead.revision,
        )
        val write = transport.write(command)
        val acknowledged = when (write) {
            is GoogleProjectionWriteResult.Blocked ->
                return blockAfterWriteAttempt(sent, write.reasonCode, nowEpochMs)
            is GoogleProjectionWriteResult.RetryableFailure ->
                return retryAfterAttempt(sent, write.reasonCode, nowEpochMs)
            is GoogleProjectionWriteResult.RevisionChanged ->
                return retryAfterAttempt(sent, "TARGET_CHANGED_DURING_WRITE", nowEpochMs)
            is GoogleProjectionWriteResult.Acknowledged -> {
                if (
                    write.fileId != binding.fileId ||
                    write.revision.isBlank() ||
                    write.writtenDigest != payload.renderedDigest
                ) {
                    return blockAfterWriteAttempt(sent, "WRITE_ACK_MISMATCH", nowEpochMs)
                }
                sent.copy(
                    state = SyncState.WRITE_ACKNOWLEDGED,
                    targetRevision = write.revision,
                    writtenDigest = write.writtenDigest,
                    errorCode = null,
                ).also { receipt ->
                    store.record(receipt, nowEpochMs, nowEpochMs)
                }
            }
        }

        return verifyReadBack(
            acknowledged = acknowledged,
            binding = binding,
            payload = payload,
            nowEpochMs = nowEpochMs,
        )
    }

    private suspend fun recordAlreadyCurrent(
        pending: SyncReceipt,
        kind: GoogleProjectionKind,
        remote: GoogleProjectionRemoteSnapshot,
        payload: GoogleProjectionPayload,
        nowEpochMs: Long,
    ): GoogleProjectionDispatchResult {
        val sent = store.markWriteSent(pending.eventId, nowEpochMs)
        val acknowledged = sent.copy(
            state = SyncState.WRITE_ACKNOWLEDGED,
            targetRevision = remote.revision,
            writtenDigest = payload.renderedDigest,
        )
        store.record(acknowledged, nowEpochMs, nowEpochMs)
        val verified = acknowledged.copy(
            state = SyncState.READ_BACK_VERIFIED,
            readBackDigest = remote.renderedDigest,
        )
        store.record(verified, nowEpochMs, nowEpochMs)
        return GoogleProjectionDispatchResult(
            state = GoogleProjectionDispatchState.SYNCED,
            receipt = verified,
            reasonCode = "ALREADY_CURRENT_${kind.name}",
        )
    }

    private suspend fun verifyReadBack(
        acknowledged: SyncReceipt,
        binding: GoogleProjectionBinding,
        payload: GoogleProjectionPayload,
        nowEpochMs: Long,
    ): GoogleProjectionDispatchResult {
        val readBack = when (val result = transport.read(binding.copy(expectedRevision = acknowledged.targetRevision))) {
            is GoogleProjectionReadResult.Blocked ->
                return blockAfterAcknowledgement(acknowledged, result.reasonCode, nowEpochMs)
            is GoogleProjectionReadResult.RetryableFailure ->
                return retryAfterAttempt(acknowledged, result.reasonCode, nowEpochMs)
            is GoogleProjectionReadResult.Found -> result.snapshot
        }
        if (readBack.fileId != binding.fileId) {
            return blockAfterAcknowledgement(acknowledged, "READ_BACK_FILE_ID_MISMATCH", nowEpochMs)
        }
        if (
            readBack.canonicalSubject != payload.subject.key ||
            readBack.canonicalDigest != payload.subject.digest
        ) {
            return if (readBack.renderedDigest != payload.renderedDigest) {
                conflict(acknowledged, readBack, nowEpochMs, "READ_BACK_IDENTITY_AND_DIGEST_MISMATCH")
            } else {
                blockAfterAcknowledgement(acknowledged, "READ_BACK_IDENTITY_MISMATCH", nowEpochMs)
            }
        }
        if (readBack.renderedDigest != payload.renderedDigest) {
            return conflict(acknowledged, readBack, nowEpochMs, "READ_BACK_DIGEST_MISMATCH")
        }

        val verified = acknowledged.copy(
            state = SyncState.READ_BACK_VERIFIED,
            targetRevision = readBack.revision,
            readBackDigest = readBack.renderedDigest,
            errorCode = null,
        )
        store.record(verified, nowEpochMs, nowEpochMs)
        return GoogleProjectionDispatchResult(
            state = GoogleProjectionDispatchState.SYNCED,
            receipt = verified,
        )
    }

    private suspend fun conflict(
        acknowledged: SyncReceipt,
        readBack: GoogleProjectionRemoteSnapshot,
        nowEpochMs: Long,
        reasonCode: String,
    ): GoogleProjectionDispatchResult {
        val receipt = acknowledged.copy(
            state = SyncState.CONFLICT,
            targetRevision = readBack.revision,
            readBackDigest = readBack.renderedDigest,
            errorCode = null,
        )
        store.record(receipt, nowEpochMs, nowEpochMs)
        return GoogleProjectionDispatchResult(
            state = GoogleProjectionDispatchState.CONFLICT,
            receipt = receipt,
            reasonCode = reasonCode,
        )
    }

    private suspend fun captureRevisionDrift(
        pending: SyncReceipt,
        binding: GoogleProjectionBinding,
        payload: GoogleProjectionPayload,
        remote: GoogleProjectionRemoteSnapshot,
        nowEpochMs: Long,
    ): ChangeProposal? {
        if (remote.canonicalSubject != payload.subject.key) return null
        if (remote.renderedDigest == payload.renderedDigest) return null
        val proposalId = proposalIdFor(pending.eventId) ?: return null
        val proposal = ChangeProposal(
            proposalId = proposalId,
            canonicalSubject = payload.subject.key,
            sourceProjectionId = binding.projectionId,
            proposer = GOOGLE_CHANGE_PROPOSER,
            requestedChangeDigest = remote.renderedDigest,
            state = ChangeProposalState.PROPOSED,
        )
        return if (store.enqueueChangeProposal(proposal, nowEpochMs)) proposal else null
    }

    private suspend fun retryWithoutWrite(
        pending: SyncReceipt,
        reasonCode: String,
        nowEpochMs: Long,
    ): GoogleProjectionDispatchResult {
        val exhausted = pending.attempts >= maxAttempts
        val receipt = pending.copy(
            state = if (exhausted) SyncState.FAILED else SyncState.RETRYABLE_FAILURE,
            errorCode = reasonCode,
        )
        val next = retryAt(nowEpochMs, pending.attempts + 1)
        store.record(receipt, next, nowEpochMs)
        return GoogleProjectionDispatchResult(
            state = if (exhausted) GoogleProjectionDispatchState.BLOCKED else GoogleProjectionDispatchState.RETRY,
            receipt = receipt,
            reasonCode = reasonCode,
        )
    }

    private suspend fun retryAfterAttempt(
        current: SyncReceipt,
        reasonCode: String,
        nowEpochMs: Long,
    ): GoogleProjectionDispatchResult {
        val exhausted = current.attempts >= maxAttempts
        val receipt = current.copy(
            state = if (exhausted) SyncState.FAILED else SyncState.RETRYABLE_FAILURE,
            errorCode = reasonCode,
        )
        val next = retryAt(nowEpochMs, current.attempts + 1)
        store.record(receipt, next, nowEpochMs)
        return GoogleProjectionDispatchResult(
            state = if (exhausted) GoogleProjectionDispatchState.BLOCKED else GoogleProjectionDispatchState.RETRY,
            receipt = receipt,
            reasonCode = reasonCode,
        )
    }

    private suspend fun blockWithoutWrite(
        pending: SyncReceipt,
        reasonCode: String,
        nowEpochMs: Long,
        proposal: ChangeProposal? = null,
    ): GoogleProjectionDispatchResult {
        val receipt = pending.copy(
            state = SyncState.FAILED,
            errorCode = reasonCode,
        )
        store.record(receipt, nowEpochMs, nowEpochMs)
        return GoogleProjectionDispatchResult(
            state = GoogleProjectionDispatchState.BLOCKED,
            receipt = receipt,
            reasonCode = reasonCode,
            changeProposal = proposal,
        )
    }

    private suspend fun blockAfterWriteAttempt(
        current: SyncReceipt,
        reasonCode: String,
        nowEpochMs: Long,
    ): GoogleProjectionDispatchResult = blockWithoutWrite(current, reasonCode, nowEpochMs)

    private suspend fun blockAfterAcknowledgement(
        current: SyncReceipt,
        reasonCode: String,
        nowEpochMs: Long,
    ): GoogleProjectionDispatchResult = blockWithoutWrite(current, reasonCode, nowEpochMs)

    private fun bindingFrom(
        receipt: SyncReceipt,
        kind: GoogleProjectionKind,
    ): GoogleProjectionBinding? {
        if (receipt.target.provider !in setOf(ExternalProvider.GOOGLE_DOCS, ExternalProvider.GOOGLE_SHEETS)) {
            return null
        }
        val match = GOOGLE_EVENT_ID_PATTERN.matchEntire(receipt.eventId) ?: return null
        return runCatching {
            GoogleProjectionBinding(
                projectionId = match.groupValues[1],
                canonicalSubject = receipt.canonicalSubject,
                kind = kind,
                fileId = receipt.target.externalId,
                expectedRevision = receipt.target.revision,
                destinationAdmission = GoogleDestinationAdmission.ADMITTED,
            )
        }.getOrNull()
    }

    private fun remoteMatches(
        remote: GoogleProjectionRemoteSnapshot,
        payload: GoogleProjectionPayload,
    ): Boolean = remote.canonicalSubject == payload.subject.key &&
        remote.canonicalDigest == payload.subject.digest &&
        remote.renderedDigest == payload.renderedDigest

    private fun proposalIdFor(eventId: String): String? {
        val match = GOOGLE_EVENT_ID_PATTERN.matchEntire(eventId) ?: return null
        return "GPROP:${match.groupValues[1]}:${match.groupValues[2]}"
    }

    private fun retryAt(nowEpochMs: Long, ordinal: Int): Long {
        val multiplier = ordinal.coerceAtLeast(1).toLong()
        val delay = if (retryDelayMs == 0L) 0L else retryDelayMs * multiplier
        return if (Long.MAX_VALUE - nowEpochMs < delay) Long.MAX_VALUE else nowEpochMs + delay
    }

    companion object {
        private val GOOGLE_CHANGE_PROPOSER = AuthorityRef(
            kind = AuthorityKind.EXTERNAL,
            ownerId = "google-workspace-projection-change",
        )
    }
}
