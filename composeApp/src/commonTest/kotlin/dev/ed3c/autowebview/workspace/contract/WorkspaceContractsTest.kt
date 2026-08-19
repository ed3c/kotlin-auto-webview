package dev.ed3c.autowebview.workspace.contract

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceContractsTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    private val shaA = DigestRef(value = "a".repeat(64))
    private val shaB = DigestRef(value = "b".repeat(64))
    private val github = AuthorityRef(AuthorityKind.GITHUB, "ed3c/kotlin-auto-webview")
    private val subjectKey = SubjectKey("REQ-120", SubjectKind.REQUIREMENT)

    @Test
    fun subjectRoundTripPreservesIdentity() {
        val subject = SubjectRef(
            key = subjectKey,
            canonicalAuthority = github,
            version = "4f0930250fdb01b693c357d2b84de9aaf7107220",
            digest = shaA,
        )

        val encoded = json.encodeToString(subject)
        assertEquals(subject, json.decodeFromString<SubjectRef>(encoded))
    }

    @Test
    fun unknownExternalProviderIsRejected() {
        val payload = """{"provider":"DROPBOX","externalId":"abc","revision":null,"canonicalUrl":null,"freshness":"UNKNOWN","observedAtEpochMs":null}"""
        assertFailsWith<SerializationException> {
            json.decodeFromString<ExternalRef>(payload)
        }
    }

    @Test
    fun publicProjectionRedactsPrivateSubjectAndRawExternalIdentity() {
        val subject = SubjectRef(
            key = SubjectKey("SRC-PRIVATE-1", SubjectKind.SOURCE),
            canonicalAuthority = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "private-owner/private-repo"),
            version = "secret-revision",
            digest = shaA,
            visibility = SubjectVisibility.PRIVATE,
            dataClass = SubjectDataClass.CONFIDENTIAL,
        )
        val external = ExternalRef(
            provider = ExternalProvider.GOOGLE_DRIVE,
            externalId = "private-file-id",
            canonicalUrl = "https://drive.google.com/file/d/private-file-id/view",
        )

        val encoded = json.encodeToString(subject.toPublicProjection(listOf(external)))
        assertTrue(encoded.contains("redacted-source"))
        assertTrue(encoded.contains("GOOGLE_DRIVE"))
        assertFalse(encoded.contains("private-owner"))
        assertFalse(encoded.contains("private-repo"))
        assertFalse(encoded.contains("private-file-id"))
        assertFalse(encoded.contains("secret-revision"))
        assertFalse(encoded.contains(shaA.value))
    }

    @Test
    fun verifiedProjectionRequiresMatchingReadBackDigest() {
        val external = ExternalRef(
            provider = ExternalProvider.GITHUB,
            externalId = "issue-120",
            revision = "v1",
            canonicalUrl = "https://github.com/ed3c/kotlin-auto-webview/issues/120",
        )

        assertFailsWith<IllegalArgumentException> {
            ProjectionRef(
                projectionId = "PROJ-120",
                canonicalSubject = subjectKey,
                kind = ProjectionKind.GITHUB,
                externalRef = external,
                state = ProjectionState.READ_BACK_VERIFIED,
                writtenDigest = shaA,
                readBackDigest = shaB,
            )
        }
    }

    @Test
    fun routeRequiresExactSubjectAndNeverGrantsExecutionAuthority() {
        assertFailsWith<IllegalArgumentException> {
            RouteRequest(
                requestId = "ROUTE-120",
                caller = github,
                intent = "route requirement",
                requiredCapabilityId = "workspace.route",
                exactSubjects = emptySet(),
                destinationOwner = AuthorityRef(AuthorityKind.ORCHESTRATOR, "bettor-arena"),
                evidenceCeiling = EvidenceCeiling.TECHNICAL,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RouteDecision(
                requestId = "ROUTE-120",
                state = RouteDecisionState.ADMITTED,
                destinationOwner = AuthorityRef(AuthorityKind.ORCHESTRATOR, "bettor-arena"),
                evidenceCeiling = EvidenceCeiling.TECHNICAL,
                reasonCode = "ROUTE_ADMITTED",
                executionAuthorityGranted = true,
            )
        }
    }

    @Test
    fun syncCannotBeVerifiedWithoutRevisionAndMatchingReadBack() {
        val target = ExternalRef(
            provider = ExternalProvider.GOOGLE_DOCS,
            externalId = "doc-1",
            revision = "drive-revision-1",
            canonicalUrl = "https://docs.google.com/document/d/doc-1/edit",
        )

        assertFailsWith<IllegalArgumentException> {
            SyncReceipt(
                eventId = "SYNC-120",
                canonicalSubject = subjectKey,
                target = target,
                state = SyncState.READ_BACK_VERIFIED,
                attempts = 1,
                targetRevision = "drive-revision-1",
                writtenDigest = shaA,
                readBackDigest = shaB,
            )
        }
    }

    @Test
    fun conflictRequiresDifferentDigests() {
        val target = ExternalRef(
            provider = ExternalProvider.GOOGLE_SHEETS,
            externalId = "sheet-1",
            canonicalUrl = "https://docs.google.com/spreadsheets/d/sheet-1/edit",
        )

        assertFailsWith<IllegalArgumentException> {
            SyncReceipt(
                eventId = "SYNC-121",
                canonicalSubject = subjectKey,
                target = target,
                state = SyncState.CONFLICT,
                attempts = 1,
                writtenDigest = shaA,
                readBackDigest = shaA,
            )
        }
    }

    @Test
    fun changeProposalCannotRepresentUnreviewedCanonicalMutation() {
        val proposal = ChangeProposal(
            proposalId = "CHANGE-120",
            canonicalSubject = subjectKey,
            sourceProjectionId = "PROJ-120",
            proposer = AuthorityRef(AuthorityKind.EXTERNAL, "google-user-edit"),
            requestedChangeDigest = shaA,
        )

        assertEquals(ChangeProposalState.PROPOSED, proposal.state)
        assertEquals(null, proposal.reviewer)
    }

    @Test
    fun digestRequiresCanonicalLowercaseSha256() {
        assertFailsWith<IllegalArgumentException> {
            DigestRef(value = "A".repeat(64))
        }
        assertFailsWith<IllegalArgumentException> {
            DigestRef(algorithm = "md5", value = "a".repeat(64))
        }
    }
}
