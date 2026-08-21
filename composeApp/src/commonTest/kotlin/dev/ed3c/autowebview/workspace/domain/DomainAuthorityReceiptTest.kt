package dev.ed3c.autowebview.workspace.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DomainAuthorityReceiptTest {
    private val validator = DomainAuthorityReceiptValidator()

    @Test
    fun exactReceiptProjectsDomainVerdictWithoutRecomputingIt() {
        val accepted = assertIs<DomainReceiptValidationResult.Accepted>(
            validator.validate(reference(), BASE_RECEIPT, CONTENT_DIGEST),
        )
        assertEquals("truth-verify-loop", accepted.projection.authorityOwner)
        assertEquals(DomainVerdictState.SUPPORTED, accepted.projection.state)
        assertEquals("synthetic-sdk-release", accepted.projection.claimId)
        assertEquals("DOMAIN_VERDICT", accepted.projection.evidenceCeiling)
        assertEquals("1".repeat(40), accepted.projection.commitSha)
        assertEquals("2".repeat(40), accepted.projection.treeSha)
        assertEquals("3".repeat(40), accepted.projection.receiptBlobSha)
    }

    @Test
    fun everyDomainOwnedVerdictStateCanBeProjectedWhenExactlyExpected() {
        DomainVerdictState.entries.forEach { state ->
            val raw = BASE_RECEIPT.replace("\"state\": \"SUPPORTED\"", "\"state\": \"${state.name}\"")
            val accepted = assertIs<DomainReceiptValidationResult.Accepted>(
                validator.validate(reference(state), raw, CONTENT_DIGEST),
            )
            assertEquals(state, accepted.projection.state)
        }
    }

    @Test
    fun contentDigestMismatchRejectsBeforeReceiptParsing() {
        assertRejected(
            validator.validate(reference(), BASE_RECEIPT, "f".repeat(64)),
            DomainReceiptRejectionReason.CONTENT_DIGEST_MISMATCH,
        )
    }

    @Test
    fun malformedReceiptIsRejected() {
        assertRejected(
            validator.validate(reference(), "{", CONTENT_DIGEST),
            DomainReceiptRejectionReason.MALFORMED_RECEIPT,
        )
    }

    @Test
    fun wrongSchemaLaneReceiptIdAuthorityAndEnvironmentAreRejected() {
        assertMutationRejected("\"schema\": \"tvl.kaw-domain-receipt.v1\"", "\"schema\": \"other\"", DomainReceiptRejectionReason.SCHEMA_MISMATCH)
        assertMutationRejected("\"lane\": \"L5_LIVE_DOMAIN_AUTHORITY_RECEIPT\"", "\"lane\": \"L4\"", DomainReceiptRejectionReason.LANE_MISMATCH)
        assertMutationRejected("\"receipt_id\": \"TVL-KAW-PUBLIC-SYNTHETIC-1\"", "\"receipt_id\": \"other\"", DomainReceiptRejectionReason.RECEIPT_ID_MISMATCH)
        assertMutationRejected("\"owner\": \"truth-verify-loop\"", "\"owner\": \"other-domain\"", DomainReceiptRejectionReason.AUTHORITY_MISMATCH)
        assertMutationRejected("\"environment\": \"PUBLIC_SYNTHETIC_CI\"", "\"environment\": \"PRODUCTION\"", DomainReceiptRejectionReason.ENVIRONMENT_MISMATCH)
    }

    @Test
    fun policyClaimVerdictAndEvidenceCeilingDriftAreRejected() {
        assertMutationRejected("\"closure_engine_blob\": \"${"4".repeat(40)}\"", "\"closure_engine_blob\": \"${"9".repeat(40)}\"", DomainReceiptRejectionReason.POLICY_MISMATCH)
        assertMutationRejected("\"claim_id\": \"synthetic-sdk-release\"", "\"claim_id\": \"other-claim\"", DomainReceiptRejectionReason.CLAIM_MISMATCH)
        assertMutationRejected("\"state\": \"SUPPORTED\"", "\"state\": \"REFUTED\"", DomainReceiptRejectionReason.VERDICT_MISMATCH)
        assertMutationRejected("\"evidence_ceiling\": \"DOMAIN_VERDICT\"", "\"evidence_ceiling\": \"USER_OUTCOME\"", DomainReceiptRejectionReason.EVIDENCE_CEILING_MISMATCH)
    }

    @Test
    fun disclosureCleanupAndBoundaryPromotionAreRejected() {
        assertMutationRejected("\"raw_source_included\": false", "\"raw_source_included\": true", DomainReceiptRejectionReason.DISCLOSURE_VIOLATION)
        assertMutationRejected("\"temporary_files_removed\": true", "\"temporary_files_removed\": false", DomainReceiptRejectionReason.CLEANUP_VIOLATION)
        assertMutationRejected("\"user_outcome\": \"ABSENT\"", "\"user_outcome\": \"PASS\"", DomainReceiptRejectionReason.EVIDENCE_BOUNDARY_WIDENED)
        assertMutationRejected("\"merge_release\": \"NOT_AUTHORIZED\"", "\"merge_release\": \"PASS\"", DomainReceiptRejectionReason.EVIDENCE_BOUNDARY_WIDENED)
    }

    @Test
    fun unknownRawEvidenceFieldFailsStrictJsonDecoding() {
        val mutated = BASE_RECEIPT.replaceFirst("{", "{\n  \"raw_evidence\": \"hidden\",")
        assertRejected(
            validator.validate(reference(), mutated, CONTENT_DIGEST),
            DomainReceiptRejectionReason.MALFORMED_RECEIPT,
        )
    }

    @Test
    fun referenceRequiresImmutableGitSubjectsAndBoundedCeiling() {
        assertFailsWith<IllegalArgumentException> { reference(commitSha = "main") }
        assertFailsWith<IllegalArgumentException> { reference(receiptPath = "../secret") }
        assertFailsWith<IllegalArgumentException> { reference(evidenceCeiling = "USER_OUTCOME") }
    }

    private fun assertMutationRejected(
        original: String,
        replacement: String,
        expected: DomainReceiptRejectionReason,
    ) {
        val mutated = BASE_RECEIPT.replace(original, replacement)
        assertRejected(validator.validate(reference(), mutated, CONTENT_DIGEST), expected)
    }

    private fun assertRejected(
        result: DomainReceiptValidationResult,
        expected: DomainReceiptRejectionReason,
    ) {
        assertEquals(expected, assertIs<DomainReceiptValidationResult.Rejected>(result).reason)
    }

    private fun reference(
        state: DomainVerdictState = DomainVerdictState.SUPPORTED,
        commitSha: String = "1".repeat(40),
        receiptPath: String = "receipts/kaw/public-claim-canary.json",
        evidenceCeiling: String = "DOMAIN_VERDICT",
    ) = DomainReceiptReference(
        repositoryFullName = "ed3c/truth-verify-loop",
        commitSha = commitSha,
        treeSha = "2".repeat(40),
        receiptPath = receiptPath,
        receiptBlobSha = "3".repeat(40),
        receiptContentSha256 = CONTENT_DIGEST,
        receiptSchema = "tvl.kaw-domain-receipt.v1",
        receiptId = "TVL-KAW-PUBLIC-SYNTHETIC-1",
        authorityOwner = "truth-verify-loop",
        lane = "L5_LIVE_DOMAIN_AUTHORITY_RECEIPT",
        environment = "PUBLIC_SYNTHETIC_CI",
        closureEngineBlob = "4".repeat(40),
        semanticVerifierSchemaBlob = "5".repeat(40),
        claimId = "synthetic-sdk-release",
        claimDigest = "6".repeat(64),
        verdictState = state,
        evidenceCeiling = evidenceCeiling,
    )

    private companion object {
        const val CONTENT_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        val BASE_RECEIPT = """
            {
              "schema": "tvl.kaw-domain-receipt.v1",
              "lane": "L5_LIVE_DOMAIN_AUTHORITY_RECEIPT",
              "receipt_id": "TVL-KAW-PUBLIC-SYNTHETIC-1",
              "authority": {"kind": "DOMAIN_REPOSITORY", "owner": "truth-verify-loop"},
              "environment": "PUBLIC_SYNTHETIC_CI",
              "policy": {
                "engine": "harness.closure.close_claim",
                "policy_version": "source-policy-default-v1",
                "closure_schema": "tvl.evidence-closure.v1",
                "closure_engine_blob": "4444444444444444444444444444444444444444",
                "semantic_verifier_schema_blob": "5555555555555555555555555555555555555555",
                "source_policy_digest": "7777777777777777777777777777777777777777777777777777777777777777"
              },
              "subject": {
                "claim_id": "synthetic-sdk-release",
                "claim_digest": "6666666666666666666666666666666666666666666666666666666666666666",
                "source_content_digest": "8888888888888888888888888888888888888888888888888888888888888888",
                "evidence_record_digest": "9999999999999999999999999999999999999999999999999999999999999999",
                "source_count": 1,
                "source_freshness": "CURRENT"
              },
              "verdict": {
                "state": "SUPPORTED",
                "closed": true,
                "as_of": "2026-08-21T00:00:00Z",
                "expires_at": "2027-08-21T00:00:00Z",
                "closure_digest": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "evidence_ceiling": "DOMAIN_VERDICT",
                "accepted_evidence_count": 1,
                "supporting_evidence_count": 1,
                "refuting_evidence_count": 0
              },
              "disclosure": {
                "class": "PUBLIC_SYNTHETIC",
                "raw_source_included": false,
                "raw_evidence_included": false,
                "credentials_included": false,
                "internal_reasoning_included": false,
                "private_locator_included": false
              },
              "cleanup": {"temporary_files_removed": true, "external_credentials_required": false},
              "evidence_boundary": {
                "other_domain_authorities": "NOT_EXERCISED",
                "private_source_access": "NOT_EXERCISED",
                "production_deployment": "NOT_EXERCISED",
                "user_outcome": "ABSENT",
                "paid_outcome": "ABSENT",
                "merge_release": "NOT_AUTHORIZED"
              }
            }
        """.trimIndent()
    }
}
