package dev.ed3c.autowebview.workspace.google

import dev.ed3c.autowebview.workspace.contract.AuthorityKind
import dev.ed3c.autowebview.workspace.contract.AuthorityRef
import dev.ed3c.autowebview.workspace.contract.ChangeProposal
import dev.ed3c.autowebview.workspace.contract.DigestRef
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectKind
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.SyncReceipt
import dev.ed3c.autowebview.workspace.contract.SyncState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleProjectionSagaTest {
    private val subjectKey = SubjectKey("REQ-creator-001", SubjectKind.REQUIREMENT)
    private val canonicalDigest = digest('a')
    private val renderedDigest = digest('b')
    private val oldRenderedDigest = digest('c')

    @Test
    fun enqueueRequiresDigestAndDestinationAdmissionAndDedupes() = runTest {
        val store = FakeStore()
        val saga = saga(store = store)
        val admitted = binding()
        val subject = subject()

        assertIs<GoogleProjectionEnqueueResult.Enqueued>(
            saga.enqueue("GPEVT:CreatorDoc:rev001", subject, admitted, nowEpochMs = 100),
        )
        assertIs<GoogleProjectionEnqueueResult.Duplicate>(
            saga.enqueue("GPEVT:CreatorDoc:rev001", subject, admitted, nowEpochMs = 101),
        )

        val localOnly = admitted.copy(
            projectionId = "LocalDoc",
            destinationAdmission = GoogleDestinationAdmission.LOCAL_ONLY,
        )
        val blocked = saga.enqueue(
            "GPEVT:LocalDoc:rev001",
            subject,
            localOnly,
            nowEpochMs = 102,
        )
        assertEquals("DESTINATION_LOCAL_ONLY", assertIs<GoogleProjectionEnqueueResult.Blocked>(blocked).reasonCode)

        val undigested = subject.copy(digest = null)
        val missingDigest = saga.enqueue(
            "GPEVT:NoDigest:rev001",
            undigested,
            admitted.copy(projectionId = "NoDigest"),
            nowEpochMs = 103,
        )
        assertEquals(
            "CANONICAL_DIGEST_ABSENT",
            assertIs<GoogleProjectionEnqueueResult.Blocked>(missingDigest).reasonCode,
        )
        assertEquals(1, store.receipts.size)
    }

    @Test
    fun successfulConditionalWriteRequiresReadBackBeforeVerified() = runTest {
        val store = FakeStore()
        val payload = payload()
        val transport = FakeTransport(
            reads = mutableListOf(
                found(revision = "r1", canonicalDigest = digest('0'), renderedDigest = oldRenderedDigest),
                found(revision = "r2", canonicalDigest = canonicalDigest, renderedDigest = renderedDigest),
            ),
            writes = mutableListOf(
                GoogleProjectionWriteResult.Acknowledged(
                    fileId = binding().fileId,
                    revision = "r2",
                    writtenDigest = renderedDigest,
                ),
            ),
        )
        val saga = saga(store, payload, transport)
        enqueue(saga)

        val result = saga.dispatchAvailable(nowEpochMs = 200).single()
        assertEquals(GoogleProjectionDispatchState.SYNCED, result.state)
        assertEquals(SyncState.READ_BACK_VERIFIED, result.receipt.state)
        assertEquals(1, result.receipt.attempts)
        assertEquals("r2", result.receipt.targetRevision)
        assertEquals(renderedDigest, result.receipt.writtenDigest)
        assertEquals(renderedDigest, result.receipt.readBackDigest)
        assertEquals(1, transport.writeCommands.size)
        assertEquals("r1", transport.writeCommands.single().ifRevisionMatches)
        assertEquals(
            listOf(
                SyncState.PENDING,
                SyncState.WRITE_SENT,
                SyncState.WRITE_ACKNOWLEDGED,
                SyncState.READ_BACK_VERIFIED,
            ),
            store.history("GPEVT:CreatorDoc:rev001"),
        )
    }

    @Test
    fun alreadyCurrentTargetIsIdempotentAndDoesNotWriteAgain() = runTest {
        val store = FakeStore()
        val transport = FakeTransport(
            reads = mutableListOf(
                found("renamed-r7", canonicalDigest, renderedDigest),
            ),
        )
        val saga = saga(store, transport = transport)
        enqueue(saga, binding = binding(expectedRevision = "old-r1", displayName = "renamed"))

        val result = saga.dispatchAvailable(nowEpochMs = 200).single()
        assertEquals(GoogleProjectionDispatchState.SYNCED, result.state)
        assertEquals(SyncState.READ_BACK_VERIFIED, result.receipt.state)
        assertEquals("ALREADY_CURRENT_DOC", result.reasonCode)
        assertTrue(transport.writeCommands.isEmpty())
        assertEquals("renamed-r7", result.receipt.targetRevision)
    }

    @Test
    fun staleRevisionNeverOverwritesManualChangeAndCreatesProposal() = runTest {
        val store = FakeStore()
        val changedDigest = digest('d')
        val transport = FakeTransport(
            reads = mutableListOf(
                found("r2", canonicalDigest = digest('e'), renderedDigest = changedDigest),
            ),
        )
        val saga = saga(store, transport = transport)
        enqueue(saga, binding = binding(expectedRevision = "r1"))

        val result = saga.dispatchAvailable(nowEpochMs = 200).single()
        assertEquals(GoogleProjectionDispatchState.BLOCKED, result.state)
        assertEquals(SyncState.FAILED, result.receipt.state)
        assertEquals("TARGET_REVISION_CHANGED", result.reasonCode)
        assertTrue(transport.writeCommands.isEmpty())
        val proposal = assertNotNull(result.changeProposal)
        assertEquals(changedDigest, proposal.requestedChangeDigest)
        assertEquals(subjectKey, proposal.canonicalSubject)
        assertEquals(1, store.proposals.size)
    }

    @Test
    fun targetChangeDuringWriteRetriesAndExhaustionFailsClosed() = runTest {
        val store = FakeStore()
        val transport = FakeTransport(
            reads = mutableListOf(
                found("r1", digest('0'), oldRenderedDigest),
                found("r1", digest('0'), oldRenderedDigest),
            ),
            writes = mutableListOf(
                GoogleProjectionWriteResult.RevisionChanged("r2"),
                GoogleProjectionWriteResult.RevisionChanged("r3"),
            ),
        )
        val saga = saga(store, transport = transport, maxAttempts = 2, retryDelayMs = 10)
        enqueue(saga)

        val first = saga.dispatchAvailable(nowEpochMs = 100).single()
        assertEquals(GoogleProjectionDispatchState.RETRY, first.state)
        assertEquals(SyncState.RETRYABLE_FAILURE, first.receipt.state)
        assertEquals(1, first.receipt.attempts)
        assertTrue(saga.dispatchAvailable(nowEpochMs = 105).isEmpty())

        val second = saga.dispatchAvailable(nowEpochMs = 120).single()
        assertEquals(GoogleProjectionDispatchState.BLOCKED, second.state)
        assertEquals(SyncState.FAILED, second.receipt.state)
        assertEquals(2, second.receipt.attempts)
        assertEquals("TARGET_CHANGED_DURING_WRITE", second.reasonCode)
        assertEquals(2, transport.writeCommands.size)
    }

    @Test
    fun postWriteReadBackMismatchBecomesConflict() = runTest {
        val store = FakeStore()
        val conflictDigest = digest('f')
        val transport = FakeTransport(
            reads = mutableListOf(
                found("r1", digest('0'), oldRenderedDigest),
                found("r3", canonicalDigest, conflictDigest),
            ),
            writes = mutableListOf(
                GoogleProjectionWriteResult.Acknowledged(
                    fileId = binding().fileId,
                    revision = "r2",
                    writtenDigest = renderedDigest,
                ),
            ),
        )
        val saga = saga(store, transport = transport)
        enqueue(saga)

        val result = saga.dispatchAvailable(nowEpochMs = 200).single()
        assertEquals(GoogleProjectionDispatchState.CONFLICT, result.state)
        assertEquals(SyncState.CONFLICT, result.receipt.state)
        assertEquals(renderedDigest, result.receipt.writtenDigest)
        assertEquals(conflictDigest, result.receipt.readBackDigest)
        assertEquals("READ_BACK_DIGEST_MISMATCH", result.reasonCode)
    }

    @Test
    fun destinationAdmissionIsRecheckedAtDispatch() = runTest {
        val store = FakeStore()
        val source = MutablePayloadSource(payload())
        val saga = saga(
            store = store,
            payload = source.payload,
            transport = FakeTransport(),
            payloadSource = source,
        )
        enqueue(saga)
        source.payload = source.payload.copy(
            destinationAdmission = GoogleDestinationAdmission.EXTERNAL_AUTHORITY_REQUIRED,
        )

        val result = saga.dispatchAvailable(nowEpochMs = 200).single()
        assertEquals(GoogleProjectionDispatchState.BLOCKED, result.state)
        assertEquals("DESTINATION_NO_LONGER_ADMITTED", result.reasonCode)
        assertEquals(1, result.receipt.attempts)
    }

    @Test
    fun observedManualEditCreatesProposalButDoesNotClaimCanonicalMutation() = runTest {
        val store = FakeStore()
        val saga = saga(store = store)
        val remote = found(
            revision = "r9",
            canonicalDigest = digest('9'),
            renderedDigest = digest('8'),
        ).snapshot

        val proposal = saga.captureObservedManualChange(
            binding = binding(),
            canonicalPayload = payload(),
            remote = remote,
            proposalId = "GPROP:CreatorDoc:manual001",
            observedAtEpochMs = 300,
        )
        assertNotNull(proposal)
        assertEquals(1, store.proposals.size)
        assertEquals(subjectKey, proposal.canonicalSubject)
        assertEquals(digest('8'), proposal.requestedChangeDigest)
    }

    @Test
    fun sameDisplayNameDoesNotCollapseDifferentFileIdentities() = runTest {
        val store = FakeStore()
        val saga = saga(store = store)
        val first = binding(
            projectionId = "DocOne",
            fileId = "drive_file_111111",
            displayName = "Same title",
        )
        val second = binding(
            projectionId = "DocTwo",
            fileId = "drive_file_222222",
            displayName = "Same title",
        )

        assertIs<GoogleProjectionEnqueueResult.Enqueued>(
            saga.enqueue("GPEVT:DocOne:rev001", subject(), first, 100),
        )
        assertIs<GoogleProjectionEnqueueResult.Enqueued>(
            saga.enqueue("GPEVT:DocTwo:rev001", subject(), second, 100),
        )
        assertEquals(2, store.receipts.size)
    }

    @Test
    fun publicReceiptOmitsGoogleIdsRevisionsAndEventIdentity() = runTest {
        val store = FakeStore()
        val transport = FakeTransport(
            reads = mutableListOf(found("private-revision-77", canonicalDigest, renderedDigest)),
        )
        val saga = saga(store, transport = transport)
        enqueue(
            saga,
            binding = binding(fileId = "private_file_secret_777", expectedRevision = "private-revision-1"),
        )
        val result = saga.dispatchAvailable(nowEpochMs = 200).single()
        val publicReceipt = result.toPublicReceipt()
        val rendered = publicReceipt.toString()

        assertFalse(rendered.contains("private_file_secret_777"))
        assertFalse(rendered.contains("private-revision"))
        assertFalse(rendered.contains("GPEVT:"))
        assertEquals(GoogleProjectionKind.DOC, publicReceipt.projectionKind)
    }

    private suspend fun enqueue(
        saga: GoogleProjectionSaga,
        binding: GoogleProjectionBinding = binding(),
    ) {
        assertIs<GoogleProjectionEnqueueResult.Enqueued>(
            saga.enqueue(
                eventId = "GPEVT:${binding.projectionId}:rev001",
                subject = subject(),
                binding = binding,
                nowEpochMs = 10,
            ),
        )
    }

    private fun saga(
        store: FakeStore,
        payload: GoogleProjectionPayload = payload(),
        transport: FakeTransport = FakeTransport(),
        payloadSource: GoogleProjectionPayloadSource = MutablePayloadSource(payload),
        maxAttempts: Int = 3,
        retryDelayMs: Long = 1_000,
    ): GoogleProjectionSaga = GoogleProjectionSaga(
        store = store,
        payloadSource = payloadSource,
        transport = transport,
        maxAttempts = maxAttempts,
        retryDelayMs = retryDelayMs,
    )

    private fun subject(): SubjectRef = SubjectRef(
        key = subjectKey,
        canonicalAuthority = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "ed3c/public-product"),
        version = "v1",
        digest = canonicalDigest,
    )

    private fun payload(): GoogleProjectionPayload = GoogleProjectionPayload(
        subject = subject(),
        renderedContent = "subject=REQ-creator-001\ndigest=${canonicalDigest.value}\n",
        renderedDigest = renderedDigest,
        destinationAdmission = GoogleDestinationAdmission.ADMITTED,
    )

    private fun binding(
        projectionId: String = "CreatorDoc",
        fileId: String = "drive_file_123456",
        expectedRevision: String? = "r1",
        displayName: String? = "Creator architecture",
    ): GoogleProjectionBinding = GoogleProjectionBinding(
        projectionId = projectionId,
        canonicalSubject = subjectKey,
        kind = GoogleProjectionKind.DOC,
        fileId = fileId,
        expectedRevision = expectedRevision,
        displayName = displayName,
        destinationAdmission = GoogleDestinationAdmission.ADMITTED,
    )

    private fun found(
        revision: String,
        canonicalDigest: DigestRef,
        renderedDigest: DigestRef,
    ): GoogleProjectionReadResult.Found = GoogleProjectionReadResult.Found(
        GoogleProjectionRemoteSnapshot(
            fileId = binding().fileId,
            revision = revision,
            canonicalSubject = subjectKey,
            canonicalDigest = canonicalDigest,
            renderedDigest = renderedDigest,
        ),
    )

    private fun digest(char: Char): DigestRef = DigestRef(value = char.toString().repeat(64))
}

private class MutablePayloadSource(
    var payload: GoogleProjectionPayload,
) : GoogleProjectionPayloadSource {
    override suspend fun render(
        canonicalSubject: SubjectKey,
        kind: GoogleProjectionKind,
    ): GoogleProjectionPayload? = payload.takeIf { it.subject.key == canonicalSubject }
}

private class FakeTransport(
    private val reads: MutableList<GoogleProjectionReadResult> = mutableListOf(),
    private val writes: MutableList<GoogleProjectionWriteResult> = mutableListOf(),
) : GoogleProjectionTransport {
    val writeCommands = mutableListOf<GoogleProjectionWriteCommand>()

    override suspend fun read(binding: GoogleProjectionBinding): GoogleProjectionReadResult =
        if (reads.isEmpty()) {
            GoogleProjectionReadResult.Blocked("NO_READ_FIXTURE")
        } else {
            reads.removeAt(0)
        }

    override suspend fun write(command: GoogleProjectionWriteCommand): GoogleProjectionWriteResult {
        writeCommands += command
        return if (writes.isEmpty()) {
            GoogleProjectionWriteResult.Blocked("NO_WRITE_FIXTURE")
        } else {
            writes.removeAt(0)
        }
    }
}

private class FakeStore : GoogleProjectionStore {
    val receipts = linkedMapOf<String, SyncReceipt>()
    val proposals = linkedMapOf<String, ChangeProposal>()
    private val dedupeKeys = linkedSetOf<String>()
    private val nextAttemptAt = linkedMapOf<String, Long>()
    private val histories = linkedMapOf<String, MutableList<SyncState>>()

    override suspend fun enqueue(
        receipt: SyncReceipt,
        dedupeKey: String,
        nextAttemptAtEpochMs: Long,
        createdAtEpochMs: Long,
    ): Boolean {
        if (dedupeKey in dedupeKeys || receipt.eventId in receipts) return false
        dedupeKeys += dedupeKey
        receipts[receipt.eventId] = receipt
        nextAttemptAt[receipt.eventId] = nextAttemptAtEpochMs
        histories.getOrPut(receipt.eventId) { mutableListOf() } += receipt.state
        return true
    }

    override suspend fun dispatchable(nowEpochMs: Long, limit: Int): List<SyncReceipt> = receipts.values
        .filter { receipt ->
            receipt.state in setOf(SyncState.PENDING, SyncState.RETRYABLE_FAILURE) &&
                (nextAttemptAt[receipt.eventId] ?: Long.MAX_VALUE) <= nowEpochMs
        }
        .take(limit)

    override suspend fun markWriteSent(eventId: String, updatedAtEpochMs: Long): SyncReceipt {
        val current = receipts.getValue(eventId)
        require(current.state == SyncState.PENDING || current.state == SyncState.RETRYABLE_FAILURE)
        val next = current.copy(
            state = SyncState.WRITE_SENT,
            attempts = current.attempts + 1,
            targetRevision = null,
            writtenDigest = null,
            readBackDigest = null,
            errorCode = null,
        )
        persist(next, updatedAtEpochMs)
        return next
    }

    override suspend fun record(
        receipt: SyncReceipt,
        nextAttemptAtEpochMs: Long,
        updatedAtEpochMs: Long,
    ) {
        val current = receipts.getValue(receipt.eventId)
        require(current.canonicalSubject == receipt.canonicalSubject)
        require(current.target == receipt.target)
        require(current.attempts == receipt.attempts)
        require(allows(current.state, receipt.state)) {
            "Invalid fake transition ${current.state} -> ${receipt.state}"
        }
        persist(receipt, nextAttemptAtEpochMs)
    }

    override suspend fun enqueueChangeProposal(
        proposal: ChangeProposal,
        receivedAtEpochMs: Long,
    ): Boolean {
        if (proposal.proposalId in proposals) return false
        proposals[proposal.proposalId] = proposal
        return true
    }

    fun history(eventId: String): List<SyncState> = histories[eventId].orEmpty()

    private fun persist(receipt: SyncReceipt, next: Long) {
        receipts[receipt.eventId] = receipt
        nextAttemptAt[receipt.eventId] = next
        histories.getOrPut(receipt.eventId) { mutableListOf() } += receipt.state
    }

    private fun allows(from: SyncState, to: SyncState): Boolean = when (from) {
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
        SyncState.RETRYABLE_FAILURE -> to in setOf(SyncState.WRITE_SENT, SyncState.FAILED)
        SyncState.READ_BACK_VERIFIED -> to == SyncState.CLEANED_UP
        SyncState.CONFLICT -> to == SyncState.CLEANED_UP
        SyncState.FAILED -> to == SyncState.CLEANED_UP
        SyncState.CLEANED_UP -> false
    }
}
