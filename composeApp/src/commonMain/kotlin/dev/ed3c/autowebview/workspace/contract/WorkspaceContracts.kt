package dev.ed3c.autowebview.workspace.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val LOGICAL_ID_PATTERN = Regex("^[A-Za-z][A-Za-z0-9._:-]{2,127}$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val SAFE_EXTERNAL_ID_PATTERN = Regex("^[^\\s]{1,512}$")

@Serializable
enum class SubjectKind {
    SOURCE,
    CLAIM,
    REQUIREMENT,
    CAPABILITY,
    TECHNOLOGY_DECISION,
    SKILL_CANDIDATE,
    SKILL,
    WORK_ITEM,
    IMPLEMENTATION,
    EVIDENCE,
    EXPERIMENT,
    OUTCOME,
    PROMPT,
    DOCUMENT,
    OTHER,
}

@Serializable
enum class SubjectVisibility {
    PUBLIC,
    PRIVATE,
    RESTRICTED,
}

@Serializable
enum class SubjectDataClass {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED,
}

@Serializable
enum class AuthorityKind {
    SOURCE_PROVIDER,
    GITHUB,
    DOMAIN_REPOSITORY,
    METHOD_REPOSITORY,
    QUALIFIER,
    EXPERIMENT_OWNER,
    RUNTIME_OWNER,
    ORCHESTRATOR,
    USER,
    EXTERNAL,
}

@Serializable
data class AuthorityRef(
    val kind: AuthorityKind,
    val ownerId: String,
) {
    init {
        require(ownerId.isNotBlank()) { "Authority owner id cannot be blank" }
        require(ownerId.length <= 256) { "Authority owner id is too long" }
        require(!ownerId.contains('\n') && !ownerId.contains('\r')) {
            "Authority owner id cannot contain newlines"
        }
    }
}

@Serializable
data class DigestRef(
    val algorithm: String = "sha256",
    val value: String,
) {
    init {
        require(algorithm == "sha256") { "Only sha256 digests are admitted in W0" }
        require(SHA256_PATTERN.matches(value)) { "SHA-256 digest must be 64 lowercase hex characters" }
    }
}

@Serializable
data class SubjectKey(
    val logicalId: String,
    val kind: SubjectKind,
) {
    init {
        require(LOGICAL_ID_PATTERN.matches(logicalId)) { "Subject logical id is invalid" }
    }
}

@Serializable
data class SubjectRef(
    val key: SubjectKey,
    val canonicalAuthority: AuthorityRef,
    val version: String? = null,
    val digest: DigestRef? = null,
    val visibility: SubjectVisibility = SubjectVisibility.PUBLIC,
    val dataClass: SubjectDataClass = SubjectDataClass.PUBLIC,
) {
    init {
        require(version == null || version.isNotBlank()) { "Subject version cannot be blank" }
        require(version == null || version.length <= 256) { "Subject version is too long" }
        if (visibility == SubjectVisibility.PUBLIC) {
            require(dataClass != SubjectDataClass.RESTRICTED) {
                "A public subject cannot be classified as restricted"
            }
        }
    }
}

@Serializable
enum class ExternalProvider {
    GITHUB,
    GOOGLE_DRIVE,
    GOOGLE_DOCS,
    GOOGLE_SHEETS,
    WEB,
    YOUTUBE,
    NOTION,
    X,
    LOCAL_FILE,
    OTHER,
}

@Serializable
enum class FreshnessState {
    CURRENT,
    STALE,
    REVOKED,
    UNKNOWN,
}

@Serializable
data class ExternalRef(
    val provider: ExternalProvider,
    val externalId: String,
    val revision: String? = null,
    val canonicalUrl: String? = null,
    val freshness: FreshnessState = FreshnessState.UNKNOWN,
    val observedAtEpochMs: Long? = null,
) {
    init {
        require(SAFE_EXTERNAL_ID_PATTERN.matches(externalId)) { "External id is invalid" }
        require(revision == null || revision.isNotBlank()) { "External revision cannot be blank" }
        require(canonicalUrl == null || canonicalUrl.startsWith("https://")) {
            "External canonical URL must use HTTPS"
        }
        require(observedAtEpochMs == null || observedAtEpochMs >= 0) {
            "Observed time cannot be negative"
        }
    }
}

@Serializable
enum class EdgeRelation {
    DERIVED_FROM,
    SUPPORTS,
    REFUTES,
    CONTRADICTS,
    IMPLEMENTS,
    DEPENDS_ON,
    BLOCKED_BY,
    QUALIFIED_BY,
    EVIDENCED_BY,
    PROJECTED_AS,
    SUPERSEDES,
    REVOKES,
    OUTCOME_OF,
}

@Serializable
enum class EvidenceClass {
    SOURCE_OBSERVATION,
    SOURCE_STATEMENT,
    TECHNICAL_RECEIPT,
    LIVE_WORKFLOW,
    USER_OUTCOME,
    PAID_OUTCOME,
    INFERENCE,
    UNKNOWN,
}

@Serializable
enum class ConfidenceLevel {
    LOW,
    MEDIUM,
    HIGH,
    NOT_APPLICABLE,
}

@Serializable
data class TypedEdge(
    val edgeId: String,
    val from: SubjectKey,
    val relation: EdgeRelation,
    val to: SubjectKey,
    val owner: AuthorityRef,
    val evidenceClass: EvidenceClass,
    val confidence: ConfidenceLevel = ConfidenceLevel.NOT_APPLICABLE,
    val provenanceReceiptId: String? = null,
) {
    init {
        require(LOGICAL_ID_PATTERN.matches(edgeId)) { "Edge id is invalid" }
        require(from != to) { "Self edges are not admitted" }
        require(provenanceReceiptId == null || provenanceReceiptId.isNotBlank()) {
            "Provenance receipt id cannot be blank"
        }
    }
}

@Serializable
enum class ProjectionKind {
    GITHUB,
    GOOGLE_DOC,
    GOOGLE_SHEET,
    KAW_LOCAL,
    OTHER,
}

@Serializable
enum class ProjectionState {
    PLANNED,
    WRITE_PENDING,
    WRITTEN,
    READ_BACK_VERIFIED,
    CONFLICT,
    REVOKED,
}

@Serializable
data class ProjectionRef(
    val projectionId: String,
    val canonicalSubject: SubjectKey,
    val kind: ProjectionKind,
    val externalRef: ExternalRef,
    val state: ProjectionState,
    val writtenDigest: DigestRef? = null,
    val readBackDigest: DigestRef? = null,
) {
    init {
        require(LOGICAL_ID_PATTERN.matches(projectionId)) { "Projection id is invalid" }
        if (state == ProjectionState.READ_BACK_VERIFIED) {
            require(writtenDigest != null) { "Verified projection requires the written digest" }
            require(readBackDigest != null) { "Verified projection requires the read-back digest" }
            require(writtenDigest == readBackDigest) { "Projection read-back digest does not match written digest" }
        }
    }
}

@Serializable
enum class EvidenceCeiling {
    SOURCE_ONLY,
    TECHNICAL,
    LIVE_WORKFLOW,
    USER_VALIDATED,
    PAID_VALIDATED,
}

@Serializable
data class RouteRequest(
    val requestId: String,
    val caller: AuthorityRef,
    val intent: String,
    val requiredCapabilityId: String,
    val exactSubjects: Set<SubjectKey>,
    val destinationOwner: AuthorityRef,
    val evidenceCeiling: EvidenceCeiling,
) {
    init {
        require(LOGICAL_ID_PATTERN.matches(requestId)) { "Route request id is invalid" }
        require(intent.isNotBlank()) { "Route intent cannot be blank" }
        require(intent.length <= 1_024) { "Route intent is too long" }
        require(requiredCapabilityId.isNotBlank()) { "Required capability id cannot be blank" }
        require(requiredCapabilityId.length <= 256) { "Required capability id is too long" }
        require(exactSubjects.isNotEmpty()) { "Route request requires at least one exact subject" }
    }
}

@Serializable
enum class RouteDecisionState {
    ADMITTED,
    REJECTED,
    DEFERRED,
}

@Serializable
data class RouteDecision(
    val requestId: String,
    val state: RouteDecisionState,
    val destinationOwner: AuthorityRef,
    val evidenceCeiling: EvidenceCeiling,
    val reasonCode: String,
    val executionAuthorityGranted: Boolean = false,
) {
    init {
        require(LOGICAL_ID_PATTERN.matches(requestId)) { "Route request id is invalid" }
        require(reasonCode.isNotBlank()) { "Route decision reason code cannot be blank" }
        require(!executionAuthorityGranted) {
            "W0 route decisions never grant execution authority"
        }
    }
}

@Serializable
enum class ReceiptStatus {
    PASS,
    FAIL,
    BLOCKED,
    NOT_EXERCISED,
    EXTERNAL_AUTHORITY_REQUIRED,
}

@Serializable
data class EvidenceReceiptRef(
    val receiptId: String,
    val owner: AuthorityRef,
    val lane: String,
    val digest: DigestRef,
    val subject: SubjectKey,
    val environment: String,
    val status: ReceiptStatus,
) {
    init {
        require(LOGICAL_ID_PATTERN.matches(receiptId)) { "Receipt id is invalid" }
        require(lane.isNotBlank()) { "Receipt lane cannot be blank" }
        require(environment.isNotBlank()) { "Receipt environment cannot be blank" }
    }
}

@Serializable
enum class ChangeProposalState {
    PROPOSED,
    ACCEPTED_FOR_CANONICAL_REVIEW,
    REJECTED,
}

@Serializable
data class ChangeProposal(
    val proposalId: String,
    val canonicalSubject: SubjectKey,
    val sourceProjectionId: String,
    val proposer: AuthorityRef,
    val requestedChangeDigest: DigestRef,
    val state: ChangeProposalState = ChangeProposalState.PROPOSED,
    val reviewer: AuthorityRef? = null,
) {
    init {
        require(LOGICAL_ID_PATTERN.matches(proposalId)) { "Change proposal id is invalid" }
        require(sourceProjectionId.isNotBlank()) { "Change proposal must reference its projection" }
        if (state != ChangeProposalState.PROPOSED) {
            require(reviewer != null) { "A reviewed change proposal requires a reviewer" }
        }
    }
}

@Serializable
enum class SyncState {
    PENDING,
    WRITE_SENT,
    WRITE_ACKNOWLEDGED,
    READ_BACK_VERIFIED,
    CONFLICT,
    RETRYABLE_FAILURE,
    FAILED,
    CLEANED_UP,
}

@Serializable
data class SyncReceipt(
    val eventId: String,
    val canonicalSubject: SubjectKey,
    val target: ExternalRef,
    val state: SyncState,
    val attempts: Int,
    val targetRevision: String? = null,
    val writtenDigest: DigestRef? = null,
    val readBackDigest: DigestRef? = null,
    val errorCode: String? = null,
) {
    init {
        require(LOGICAL_ID_PATTERN.matches(eventId)) { "Sync event id is invalid" }
        require(attempts >= 0) { "Sync attempts cannot be negative" }
        if (state == SyncState.READ_BACK_VERIFIED) {
            require(attempts > 0) { "Verified sync must have at least one attempt" }
            require(!targetRevision.isNullOrBlank()) { "Verified sync requires a target revision" }
            require(writtenDigest != null) { "Verified sync requires a written digest" }
            require(readBackDigest != null) { "Verified sync requires a read-back digest" }
            require(writtenDigest == readBackDigest) { "Sync read-back digest does not match written digest" }
        }
        if (state == SyncState.CONFLICT) {
            require(writtenDigest != null && readBackDigest != null) {
                "Conflict requires both written and read-back digests"
            }
            require(writtenDigest != readBackDigest) {
                "Conflict requires different written and read-back digests"
            }
        }
        if (state == SyncState.RETRYABLE_FAILURE || state == SyncState.FAILED) {
            require(!errorCode.isNullOrBlank()) { "Failure state requires an error code" }
        }
    }
}

@Serializable
@SerialName("public_subject_projection")
data class PublicSubjectProjection(
    val logicalId: String,
    val kind: SubjectKind,
    val authorityKind: AuthorityKind,
    val visibility: SubjectVisibility,
    val dataClass: SubjectDataClass,
    val externalProviders: Set<ExternalProvider>,
)

fun SubjectRef.toPublicProjection(externalRefs: Collection<ExternalRef> = emptyList()): PublicSubjectProjection {
    val publicLogicalId = if (
        visibility == SubjectVisibility.PUBLIC &&
        dataClass == SubjectDataClass.PUBLIC
    ) {
        key.logicalId
    } else {
        "redacted-${key.kind.name.lowercase()}"
    }

    return PublicSubjectProjection(
        logicalId = publicLogicalId,
        kind = key.kind,
        authorityKind = canonicalAuthority.kind,
        visibility = visibility,
        dataClass = dataClass,
        externalProviders = externalRefs.mapTo(linkedSetOf()) { it.provider },
    )
}
