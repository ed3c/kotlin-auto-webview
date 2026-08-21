package dev.ed3c.autowebview.workspace.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val DOMAIN_SHA40 = Regex("^[0-9a-f]{40}$")
private val DOMAIN_SHA256 = Regex("^[0-9a-f]{64}$")
private val DOMAIN_REPOSITORY = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
private val DOMAIN_PATH = Regex("^[A-Za-z0-9._/-]{1,512}$")
private val DOMAIN_ID = Regex("^[A-Za-z0-9._:-]{1,256}$")

@Serializable
enum class DomainVerdictState {
    SUPPORTED,
    REFUTED,
    CONFLICTED,
    STALE,
    UNVERIFIABLE,
}

@Serializable
data class DomainReceiptReference(
    val repositoryFullName: String,
    val commitSha: String,
    val treeSha: String,
    val receiptPath: String,
    val receiptBlobSha: String,
    val receiptContentSha256: String,
    val receiptSchema: String,
    val receiptId: String,
    val authorityOwner: String,
    val lane: String,
    val environment: String,
    val closureEngineBlob: String,
    val semanticVerifierSchemaBlob: String,
    val claimId: String,
    val claimDigest: String,
    val verdictState: DomainVerdictState,
    val evidenceCeiling: String,
) {
    init {
        require(DOMAIN_REPOSITORY.matches(repositoryFullName)) {
            "Domain receipt repository is invalid"
        }
        require(DOMAIN_SHA40.matches(commitSha)) { "Domain receipt commit must be exact" }
        require(DOMAIN_SHA40.matches(treeSha)) { "Domain receipt tree must be exact" }
        require(DOMAIN_PATH.matches(receiptPath) && !receiptPath.contains("..")) {
            "Domain receipt path is invalid"
        }
        require(DOMAIN_SHA40.matches(receiptBlobSha)) { "Domain receipt blob must be exact" }
        require(DOMAIN_SHA256.matches(receiptContentSha256)) {
            "Domain receipt content digest must be SHA-256"
        }
        require(DOMAIN_ID.matches(receiptSchema)) { "Domain receipt schema is invalid" }
        require(DOMAIN_ID.matches(receiptId)) { "Domain receipt id is invalid" }
        require(DOMAIN_ID.matches(authorityOwner)) { "Domain receipt authority is invalid" }
        require(DOMAIN_ID.matches(lane)) { "Domain receipt lane is invalid" }
        require(DOMAIN_ID.matches(environment)) { "Domain receipt environment is invalid" }
        require(DOMAIN_SHA40.matches(closureEngineBlob)) { "Closure engine blob is invalid" }
        require(DOMAIN_SHA40.matches(semanticVerifierSchemaBlob)) {
            "Semantic verifier schema blob is invalid"
        }
        require(DOMAIN_ID.matches(claimId)) { "Domain claim id is invalid" }
        require(DOMAIN_SHA256.matches(claimDigest)) { "Domain claim digest is invalid" }
        require(evidenceCeiling == "DOMAIN_VERDICT") {
            "KAW admits only the bounded domain-verdict ceiling"
        }
    }
}

@Serializable
data class DomainAuthorityProjection(
    val authorityOwner: String,
    val repositoryFullName: String,
    val commitSha: String,
    val treeSha: String,
    val receiptBlobSha: String,
    val receiptContentSha256: String,
    val receiptId: String,
    val claimId: String,
    val claimDigest: String,
    val state: DomainVerdictState,
    val closed: Boolean,
    val asOf: String,
    val expiresAt: String?,
    val closureDigest: String,
    val sourceFreshness: String,
    val acceptedEvidenceCount: Int,
    val supportingEvidenceCount: Int,
    val refutingEvidenceCount: Int,
    val evidenceCeiling: String,
) {
    init {
        require(authorityOwner.isNotBlank()) { "Projected authority cannot be blank" }
        require(DOMAIN_SHA40.matches(commitSha)) { "Projected commit must be exact" }
        require(DOMAIN_SHA40.matches(treeSha)) { "Projected tree must be exact" }
        require(DOMAIN_SHA40.matches(receiptBlobSha)) { "Projected blob must be exact" }
        require(DOMAIN_SHA256.matches(receiptContentSha256)) {
            "Projected receipt digest must be SHA-256"
        }
        require(DOMAIN_SHA256.matches(claimDigest)) { "Projected claim digest is invalid" }
        require(DOMAIN_SHA256.matches(closureDigest)) { "Projected closure digest is invalid" }
        require(acceptedEvidenceCount >= 0) { "Accepted-evidence count cannot be negative" }
        require(supportingEvidenceCount >= 0) { "Supporting-evidence count cannot be negative" }
        require(refutingEvidenceCount >= 0) { "Refuting-evidence count cannot be negative" }
        require(evidenceCeiling == "DOMAIN_VERDICT") {
            "Projected evidence ceiling cannot be widened"
        }
    }
}

@Serializable
enum class DomainReceiptRejectionReason {
    CONTENT_DIGEST_MISMATCH,
    MALFORMED_RECEIPT,
    SCHEMA_MISMATCH,
    LANE_MISMATCH,
    RECEIPT_ID_MISMATCH,
    AUTHORITY_MISMATCH,
    ENVIRONMENT_MISMATCH,
    POLICY_MISMATCH,
    CLAIM_MISMATCH,
    VERDICT_MISMATCH,
    EVIDENCE_CEILING_MISMATCH,
    DISCLOSURE_VIOLATION,
    CLEANUP_VIOLATION,
    EVIDENCE_BOUNDARY_WIDENED,
}

sealed interface DomainReceiptValidationResult {
    data class Accepted(val projection: DomainAuthorityProjection) : DomainReceiptValidationResult

    data class Rejected(val reason: DomainReceiptRejectionReason) : DomainReceiptValidationResult
}

class DomainAuthorityReceiptValidator(
    private val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    },
) {
    fun validate(
        reference: DomainReceiptReference,
        rawReceipt: String,
        observedContentSha256: String,
    ): DomainReceiptValidationResult {
        if (
            !DOMAIN_SHA256.matches(observedContentSha256) ||
            observedContentSha256 != reference.receiptContentSha256
        ) {
            return rejected(DomainReceiptRejectionReason.CONTENT_DIGEST_MISMATCH)
        }
        val receipt = try {
            json.decodeFromString<TruthVerifyDomainReceipt>(rawReceipt)
        } catch (_: Exception) {
            return rejected(DomainReceiptRejectionReason.MALFORMED_RECEIPT)
        }
        if (receipt.schema != reference.receiptSchema) {
            return rejected(DomainReceiptRejectionReason.SCHEMA_MISMATCH)
        }
        if (receipt.lane != reference.lane) {
            return rejected(DomainReceiptRejectionReason.LANE_MISMATCH)
        }
        if (receipt.receiptId != reference.receiptId) {
            return rejected(DomainReceiptRejectionReason.RECEIPT_ID_MISMATCH)
        }
        if (
            receipt.authority.kind != "DOMAIN_REPOSITORY" ||
            receipt.authority.owner != reference.authorityOwner
        ) {
            return rejected(DomainReceiptRejectionReason.AUTHORITY_MISMATCH)
        }
        if (receipt.environment != reference.environment) {
            return rejected(DomainReceiptRejectionReason.ENVIRONMENT_MISMATCH)
        }
        if (
            receipt.policy.engine != "harness.closure.close_claim" ||
            receipt.policy.closureSchema != "tvl.evidence-closure.v1" ||
            receipt.policy.closureEngineBlob != reference.closureEngineBlob ||
            receipt.policy.semanticVerifierSchemaBlob != reference.semanticVerifierSchemaBlob ||
            !DOMAIN_SHA256.matches(receipt.policy.sourcePolicyDigest)
        ) {
            return rejected(DomainReceiptRejectionReason.POLICY_MISMATCH)
        }
        if (
            receipt.subject.claimId != reference.claimId ||
            receipt.subject.claimDigest != reference.claimDigest ||
            !DOMAIN_SHA256.matches(receipt.subject.sourceContentDigest) ||
            !DOMAIN_SHA256.matches(receipt.subject.evidenceRecordDigest) ||
            receipt.subject.sourceCount <= 0
        ) {
            return rejected(DomainReceiptRejectionReason.CLAIM_MISMATCH)
        }
        if (receipt.verdict.state != reference.verdictState) {
            return rejected(DomainReceiptRejectionReason.VERDICT_MISMATCH)
        }
        if (
            receipt.verdict.evidenceCeiling != reference.evidenceCeiling ||
            receipt.verdict.evidenceCeiling != "DOMAIN_VERDICT"
        ) {
            return rejected(DomainReceiptRejectionReason.EVIDENCE_CEILING_MISMATCH)
        }
        if (!DOMAIN_SHA256.matches(receipt.verdict.closureDigest)) {
            return rejected(DomainReceiptRejectionReason.VERDICT_MISMATCH)
        }
        if (
            receipt.disclosure.classification != "PUBLIC_SYNTHETIC" ||
            receipt.disclosure.rawSourceIncluded ||
            receipt.disclosure.rawEvidenceIncluded ||
            receipt.disclosure.credentialsIncluded ||
            receipt.disclosure.internalReasoningIncluded ||
            receipt.disclosure.privateLocatorIncluded
        ) {
            return rejected(DomainReceiptRejectionReason.DISCLOSURE_VIOLATION)
        }
        if (
            !receipt.cleanup.temporaryFilesRemoved ||
            receipt.cleanup.externalCredentialsRequired
        ) {
            return rejected(DomainReceiptRejectionReason.CLEANUP_VIOLATION)
        }
        if (
            receipt.evidenceBoundary.otherDomainAuthorities != "NOT_EXERCISED" ||
            receipt.evidenceBoundary.privateSourceAccess != "NOT_EXERCISED" ||
            receipt.evidenceBoundary.productionDeployment != "NOT_EXERCISED" ||
            receipt.evidenceBoundary.userOutcome != "ABSENT" ||
            receipt.evidenceBoundary.paidOutcome != "ABSENT" ||
            receipt.evidenceBoundary.mergeRelease != "NOT_AUTHORIZED"
        ) {
            return rejected(DomainReceiptRejectionReason.EVIDENCE_BOUNDARY_WIDENED)
        }
        return DomainReceiptValidationResult.Accepted(
            DomainAuthorityProjection(
                authorityOwner = receipt.authority.owner,
                repositoryFullName = reference.repositoryFullName,
                commitSha = reference.commitSha,
                treeSha = reference.treeSha,
                receiptBlobSha = reference.receiptBlobSha,
                receiptContentSha256 = reference.receiptContentSha256,
                receiptId = receipt.receiptId,
                claimId = receipt.subject.claimId,
                claimDigest = receipt.subject.claimDigest,
                state = receipt.verdict.state,
                closed = receipt.verdict.closed,
                asOf = receipt.verdict.asOf,
                expiresAt = receipt.verdict.expiresAt,
                closureDigest = receipt.verdict.closureDigest,
                sourceFreshness = receipt.subject.sourceFreshness,
                acceptedEvidenceCount = receipt.verdict.acceptedEvidenceCount,
                supportingEvidenceCount = receipt.verdict.supportingEvidenceCount,
                refutingEvidenceCount = receipt.verdict.refutingEvidenceCount,
                evidenceCeiling = receipt.verdict.evidenceCeiling,
            ),
        )
    }

    private fun rejected(reason: DomainReceiptRejectionReason) =
        DomainReceiptValidationResult.Rejected(reason)
}

@Serializable
private data class TruthVerifyDomainReceipt(
    val schema: String,
    val lane: String,
    @kotlinx.serialization.SerialName("receipt_id") val receiptId: String,
    val authority: ReceiptAuthority,
    val environment: String,
    val policy: ReceiptPolicy,
    val subject: ReceiptSubject,
    val verdict: ReceiptVerdict,
    val disclosure: ReceiptDisclosure,
    val cleanup: ReceiptCleanup,
    @kotlinx.serialization.SerialName("evidence_boundary")
    val evidenceBoundary: ReceiptEvidenceBoundary,
)

@Serializable
private data class ReceiptAuthority(val kind: String, val owner: String)

@Serializable
private data class ReceiptPolicy(
    val engine: String,
    @kotlinx.serialization.SerialName("policy_version") val policyVersion: String,
    @kotlinx.serialization.SerialName("closure_schema") val closureSchema: String,
    @kotlinx.serialization.SerialName("closure_engine_blob") val closureEngineBlob: String,
    @kotlinx.serialization.SerialName("semantic_verifier_schema_blob")
    val semanticVerifierSchemaBlob: String,
    @kotlinx.serialization.SerialName("source_policy_digest") val sourcePolicyDigest: String,
)

@Serializable
private data class ReceiptSubject(
    @kotlinx.serialization.SerialName("claim_id") val claimId: String,
    @kotlinx.serialization.SerialName("claim_digest") val claimDigest: String,
    @kotlinx.serialization.SerialName("source_content_digest") val sourceContentDigest: String,
    @kotlinx.serialization.SerialName("evidence_record_digest") val evidenceRecordDigest: String,
    @kotlinx.serialization.SerialName("source_count") val sourceCount: Int,
    @kotlinx.serialization.SerialName("source_freshness") val sourceFreshness: String,
)

@Serializable
private data class ReceiptVerdict(
    val state: DomainVerdictState,
    val closed: Boolean,
    @kotlinx.serialization.SerialName("as_of") val asOf: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String? = null,
    @kotlinx.serialization.SerialName("closure_digest") val closureDigest: String,
    @kotlinx.serialization.SerialName("evidence_ceiling") val evidenceCeiling: String,
    @kotlinx.serialization.SerialName("accepted_evidence_count") val acceptedEvidenceCount: Int,
    @kotlinx.serialization.SerialName("supporting_evidence_count") val supportingEvidenceCount: Int,
    @kotlinx.serialization.SerialName("refuting_evidence_count") val refutingEvidenceCount: Int,
)

@Serializable
private data class ReceiptDisclosure(
    @kotlinx.serialization.SerialName("class") val classification: String,
    @kotlinx.serialization.SerialName("raw_source_included") val rawSourceIncluded: Boolean,
    @kotlinx.serialization.SerialName("raw_evidence_included") val rawEvidenceIncluded: Boolean,
    @kotlinx.serialization.SerialName("credentials_included") val credentialsIncluded: Boolean,
    @kotlinx.serialization.SerialName("internal_reasoning_included")
    val internalReasoningIncluded: Boolean,
    @kotlinx.serialization.SerialName("private_locator_included") val privateLocatorIncluded: Boolean,
)

@Serializable
private data class ReceiptCleanup(
    @kotlinx.serialization.SerialName("temporary_files_removed") val temporaryFilesRemoved: Boolean,
    @kotlinx.serialization.SerialName("external_credentials_required")
    val externalCredentialsRequired: Boolean,
)

@Serializable
private data class ReceiptEvidenceBoundary(
    @kotlinx.serialization.SerialName("other_domain_authorities")
    val otherDomainAuthorities: String,
    @kotlinx.serialization.SerialName("private_source_access") val privateSourceAccess: String,
    @kotlinx.serialization.SerialName("production_deployment") val productionDeployment: String,
    @kotlinx.serialization.SerialName("user_outcome") val userOutcome: String,
    @kotlinx.serialization.SerialName("paid_outcome") val paidOutcome: String,
    @kotlinx.serialization.SerialName("merge_release") val mergeRelease: String,
)
