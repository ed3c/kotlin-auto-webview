package dev.ed3c.autowebview.workspace.routing

import dev.ed3c.autowebview.workspace.contract.AuthorityKind
import dev.ed3c.autowebview.workspace.contract.AuthorityRef
import dev.ed3c.autowebview.workspace.contract.DigestRef
import dev.ed3c.autowebview.workspace.contract.EvidenceCeiling
import dev.ed3c.autowebview.workspace.contract.RouteRequest
import dev.ed3c.autowebview.workspace.contract.SubjectDataClass
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectKind
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import dev.ed3c.autowebview.workspace.contract.SubjectVisibility
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FederationRouterTest {
    private val subjectKey = SubjectKey("REQ.route-001", SubjectKind.REQUIREMENT)
    private val digest = DigestRef(value = "a".repeat(64))
    private val caller = AuthorityRef(AuthorityKind.USER, "workspace-user")
    private val verifier = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "truth-verify-loop")

    @Test
    fun exactPublicRouteProducesProposalWithoutExecutionAuthority() = runTest {
        val subject = subject()
        val sink = RecordingProposalSink(
            RouteProposalResponse.Acknowledged(
                RouteProposalReceipt(
                    requestId = "ROUTE.verify-001",
                    routeClass = FederationRouteClass.VERIFY_CLAIM,
                    destinationOwner = verifier,
                    evidenceCeiling = EvidenceCeiling.TECHNICAL,
                ),
            ),
        )
        val router = router(subject, sink)

        val result = router.route(
            envelope(
                requestId = "ROUTE.verify-001",
                capabilityId = "verify.claim",
                destination = verifier,
                expectedDigest = digest,
            ),
        )

        assertEquals(FederationRouteOutcomeState.PROPOSED, result.state)
        assertEquals(FederationRouteClass.VERIFY_CLAIM, result.routeClass)
        assertFalse(result.decision.executionAuthorityGranted)
        assertEquals(1, sink.packets.size)
        assertEquals(setOf(subjectKey), sink.packets.single().exactSubjects)
    }

    @Test
    fun unknownCapabilityFailsClosedWithoutCallingDestination() = runTest {
        val sink = RecordingProposalSink(RouteProposalResponse.TimedOut())
        val result = router(subject(), sink).route(
            envelope(
                requestId = "ROUTE.unknown-001",
                capabilityId = "unknown.capability",
                destination = verifier,
                expectedDigest = digest,
            ),
        )

        assertEquals(FederationRouteOutcomeState.REJECTED, result.state)
        assertEquals("UNKNOWN_CAPABILITY", result.decision.reasonCode)
        assertTrue(sink.packets.isEmpty())
    }

    @Test
    fun wrongOwnerAndExcessEvidenceCannotWidenAuthority() = runTest {
        val sink = RecordingProposalSink(RouteProposalResponse.TimedOut())
        val wrongOwner = AuthorityRef(AuthorityKind.ORCHESTRATOR, "bettor-arena")
        val ownerMismatch = router(subject(), sink).route(
            envelope(
                requestId = "ROUTE.owner-001",
                capabilityId = "verify.claim",
                destination = wrongOwner,
                expectedDigest = digest,
            ),
        )
        assertEquals("DESTINATION_OWNER_MISMATCH", ownerMismatch.decision.reasonCode)

        val liveRequest = request(
            requestId = "ROUTE.evidence-001",
            capabilityId = "verify.claim",
            destination = verifier,
            evidenceCeiling = EvidenceCeiling.LIVE_WORKFLOW,
        )
        val excessive = router(subject(), sink).route(
            FederationRouteEnvelope(
                request = liveRequest,
                subjects = setOf(expectation(digest)),
            ),
        )
        assertEquals("EVIDENCE_CEILING_EXCEEDS_ROUTE", excessive.decision.reasonCode)
        assertTrue(sink.packets.isEmpty())
    }

    @Test
    fun staleVersionOrDigestIsRejectedBeforeHandoff() = runTest {
        val sink = RecordingProposalSink(RouteProposalResponse.TimedOut())
        val actual = subject(version = "v2")
        val staleVersion = router(actual, sink).route(
            envelope(
                requestId = "ROUTE.stale-version",
                capabilityId = "verify.claim",
                destination = verifier,
                expectedDigest = digest,
                expectedVersion = "v1",
            ),
        )
        assertEquals("STALE_SUBJECT_VERSION", staleVersion.decision.reasonCode)

        val staleDigest = router(actual, sink).route(
            envelope(
                requestId = "ROUTE.stale-digest",
                capabilityId = "verify.claim",
                destination = verifier,
                expectedDigest = DigestRef(value = "b".repeat(64)),
                expectedVersion = "v2",
            ),
        )
        assertEquals("STALE_SUBJECT_DIGEST", staleDigest.decision.reasonCode)
        assertTrue(sink.packets.isEmpty())
    }

    @Test
    fun publicWorkItemRouteRejectsConfidentialSubjectByDefault() = runTest {
        val github = AuthorityRef(AuthorityKind.GITHUB, "github-workgraph")
        val confidential = subject(
            visibility = SubjectVisibility.PRIVATE,
            dataClass = SubjectDataClass.CONFIDENTIAL,
        )
        val sink = RecordingProposalSink(RouteProposalResponse.TimedOut())
        val result = router(confidential, sink).route(
            envelope(
                requestId = "ROUTE.private-to-public",
                capabilityId = "open.work-item",
                destination = github,
                expectedDigest = digest,
            ),
        )

        assertEquals("DESTINATION_DATA_CLASS_INSUFFICIENT", result.decision.reasonCode)
        assertTrue(sink.packets.isEmpty())
    }

    @Test
    fun explicitConfidentialBindingCanAdmitConfidentialSubjectWithoutGrantingExecution() = runTest {
        val confidentialOwner = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "confidential-verifier")
        val catalog = FederationRouteCatalog(
            listOf(
                FederationRouteBinding(
                    capabilityId = "verify.confidential",
                    routeClass = FederationRouteClass.VERIFY_CLAIM,
                    destinationOwner = confidentialOwner,
                    maximumDataClass = SubjectDataClass.CONFIDENTIAL,
                    maximumEvidenceCeiling = EvidenceCeiling.TECHNICAL,
                ),
            ),
        )
        val subject = subject(
            visibility = SubjectVisibility.PRIVATE,
            dataClass = SubjectDataClass.CONFIDENTIAL,
        )
        val sink = RecordingProposalSink(
            RouteProposalResponse.Acknowledged(
                RouteProposalReceipt(
                    requestId = "ROUTE.confidential-001",
                    routeClass = FederationRouteClass.VERIFY_CLAIM,
                    destinationOwner = confidentialOwner,
                    evidenceCeiling = EvidenceCeiling.TECHNICAL,
                ),
            ),
        )
        val router = FederationRouter(
            catalog = catalog,
            subjectSource = mapSource(subject),
            requestLedger = InMemoryRouteRequestLedger(),
            proposalSink = sink,
        )
        val result = router.route(
            envelope(
                requestId = "ROUTE.confidential-001",
                capabilityId = "verify.confidential",
                destination = confidentialOwner,
                expectedDigest = digest,
            ),
        )

        assertEquals(FederationRouteOutcomeState.PROPOSED, result.state)
        assertFalse(result.decision.executionAuthorityGranted)
    }

    @Test
    fun duplicateRequestIsIdempotentButSemanticReuseIsRejected() = runTest {
        val subject = subject()
        val sink = RecordingProposalSink(
            RouteProposalResponse.Acknowledged(
                RouteProposalReceipt(
                    requestId = "ROUTE.replay-001",
                    routeClass = FederationRouteClass.VERIFY_CLAIM,
                    destinationOwner = verifier,
                    evidenceCeiling = EvidenceCeiling.TECHNICAL,
                ),
            ),
        )
        val ledger = InMemoryRouteRequestLedger()
        val router = FederationRouter(
            catalog = StandardFederationRouteCatalog.value,
            subjectSource = mapSource(subject),
            requestLedger = ledger,
            proposalSink = sink,
        )
        val original = envelope(
            requestId = "ROUTE.replay-001",
            capabilityId = "verify.claim",
            destination = verifier,
            expectedDigest = digest,
        )
        val first = router.route(original)
        val replay = router.route(original)
        assertFalse(first.idempotentReplay)
        assertTrue(replay.idempotentReplay)

        val changed = FederationRouteEnvelope(
            request = original.request.copy(intent = "different semantic intent"),
            subjects = original.subjects,
        )
        val conflict = router.route(changed)
        assertEquals("REQUEST_ID_SEMANTIC_CONFLICT", conflict.decision.reasonCode)
    }

    @Test
    fun destinationDenialTimeoutAndMismatchedReceiptRemainNonExecutionStates() = runTest {
        val denialSink = RecordingProposalSink(RouteProposalResponse.Denied("POLICY_DENIED"))
        val denied = router(subject(), denialSink).route(
            envelope(
                requestId = "ROUTE.denied-001",
                capabilityId = "verify.claim",
                destination = verifier,
                expectedDigest = digest,
            ),
        )
        assertEquals(FederationRouteOutcomeState.DEFERRED, denied.state)
        assertEquals("POLICY_DENIED", denied.decision.reasonCode)

        val timeoutSink = RecordingProposalSink(RouteProposalResponse.TimedOut())
        val timeout = router(subject(), timeoutSink).route(
            envelope(
                requestId = "ROUTE.timeout-001",
                capabilityId = "verify.claim",
                destination = verifier,
                expectedDigest = digest,
            ),
        )
        assertEquals(FederationRouteOutcomeState.DEFERRED, timeout.state)
        assertEquals("DESTINATION_TIMEOUT", timeout.decision.reasonCode)

        val mismatchSink = RecordingProposalSink(
            RouteProposalResponse.Acknowledged(
                RouteProposalReceipt(
                    requestId = "ROUTE.other-request",
                    routeClass = FederationRouteClass.VERIFY_CLAIM,
                    destinationOwner = verifier,
                    evidenceCeiling = EvidenceCeiling.TECHNICAL,
                ),
            ),
        )
        val mismatch = router(subject(), mismatchSink).route(
            envelope(
                requestId = "ROUTE.mismatch-001",
                capabilityId = "verify.claim",
                destination = verifier,
                expectedDigest = digest,
            ),
        )
        assertEquals(FederationRouteOutcomeState.REJECTED, mismatch.state)
        assertEquals("DESTINATION_RECEIPT_MISMATCH", mismatch.decision.reasonCode)
        assertFalse(mismatch.decision.executionAuthorityGranted)
    }

    @Test
    fun ambiguousCapabilityBindingsAreRejectedAtCatalogConstruction() {
        assertFailsWith<IllegalArgumentException> {
            FederationRouteCatalog(
                listOf(
                    FederationRouteBinding(
                        capabilityId = "ambiguous",
                        routeClass = FederationRouteClass.COMPILE_CONTENT,
                        destinationOwner = verifier,
                        maximumDataClass = SubjectDataClass.PUBLIC,
                        maximumEvidenceCeiling = EvidenceCeiling.SOURCE_ONLY,
                    ),
                    FederationRouteBinding(
                        capabilityId = "ambiguous",
                        routeClass = FederationRouteClass.COMPILE_CONTENT,
                        destinationOwner = AuthorityRef(AuthorityKind.ORCHESTRATOR, "other-owner"),
                        maximumDataClass = SubjectDataClass.PUBLIC,
                        maximumEvidenceCeiling = EvidenceCeiling.SOURCE_ONLY,
                    ),
                ),
            )
        }
    }

    private fun router(subject: SubjectRef, sink: RecordingProposalSink): FederationRouter = FederationRouter(
        catalog = StandardFederationRouteCatalog.value,
        subjectSource = mapSource(subject),
        requestLedger = InMemoryRouteRequestLedger(),
        proposalSink = sink,
    )

    private fun mapSource(subject: SubjectRef): FederationRouteSubjectSource =
        FederationRouteSubjectSource { key -> if (key == subject.key) subject else null }

    private fun subject(
        version: String = "v2",
        visibility: SubjectVisibility = SubjectVisibility.PUBLIC,
        dataClass: SubjectDataClass = SubjectDataClass.PUBLIC,
    ): SubjectRef = SubjectRef(
        key = subjectKey,
        canonicalAuthority = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "subject-owner"),
        version = version,
        digest = digest,
        visibility = visibility,
        dataClass = dataClass,
    )

    private fun request(
        requestId: String,
        capabilityId: String,
        destination: AuthorityRef,
        evidenceCeiling: EvidenceCeiling = EvidenceCeiling.TECHNICAL,
    ): RouteRequest = RouteRequest(
        requestId = requestId,
        caller = caller,
        intent = "route the exact requirement",
        requiredCapabilityId = capabilityId,
        exactSubjects = setOf(subjectKey),
        destinationOwner = destination,
        evidenceCeiling = evidenceCeiling,
    )

    private fun expectation(
        expectedDigest: DigestRef,
        expectedVersion: String = "v2",
    ) = ExactSubjectExpectation(
        key = subjectKey,
        expectedVersion = expectedVersion,
        expectedDigest = expectedDigest,
    )

    private fun envelope(
        requestId: String,
        capabilityId: String,
        destination: AuthorityRef,
        expectedDigest: DigestRef,
        expectedVersion: String = "v2",
    ) = FederationRouteEnvelope(
        request = request(requestId, capabilityId, destination),
        subjects = setOf(expectation(expectedDigest, expectedVersion)),
    )
}

private class RecordingProposalSink(
    var response: RouteProposalResponse,
) : RouteProposalSink {
    val packets = mutableListOf<RouteProposalPacket>()

    override suspend fun propose(packet: RouteProposalPacket): RouteProposalResponse {
        packets += packet
        return response
    }
}
