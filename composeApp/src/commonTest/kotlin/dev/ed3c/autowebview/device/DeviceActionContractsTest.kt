package dev.ed3c.autowebview.device

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.contract.DeviceActionKind
import dev.ed3c.autowebview.device.contract.DeviceActionPayload
import dev.ed3c.autowebview.device.contract.DeviceActionProposal
import dev.ed3c.autowebview.device.contract.DeviceActionResult
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import dev.ed3c.autowebview.device.contract.DeviceConfirmationReceipt
import dev.ed3c.autowebview.device.contract.DeviceContractSchema
import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.contract.DeviceVerificationEvidence
import dev.ed3c.autowebview.device.contract.DeviceVerifierOutcome
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DistributionProfile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceActionContractsTest {
    private val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    @Test
    fun proposal_round_trips_with_stable_schema_and_sealed_payload() {
        val proposal = proposal()
        val encoded = json.encodeToString(DeviceActionProposal.serializer(), proposal)
        assertTrue(encoded.contains("\"schemaVersion\":\"${DeviceContractSchema.VERSION}\""))
        assertTrue(encoded.contains("\"type\":\"ui_click\""))
        assertEquals(proposal, json.decodeFromString(DeviceActionProposal.serializer(), encoded))
    }

    @Test
    fun unknown_payload_and_profile_fail_closed_during_deserialization() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(DeviceActionPayload.serializer(), "{\"type\":\"run_shell\"}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(DistributionProfile.serializer(), "\"ROOT\"")
        }
    }

    @Test
    fun wildcard_package_and_command_like_resource_tokens_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            DeviceSubjectRef("com.example.*", "window-1", "display-0", 1, 10)
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceTargetRef.ResourceTarget("settings", "wifi;rm")
        }
    }

    @Test
    fun control_characters_and_oversized_payloads_are_rejected() {
        assertFailsWith<IllegalArgumentException> { DeviceActionPayload.UiFillText("bad\u0000text") }
        assertFailsWith<IllegalArgumentException> { DeviceActionPayload.UiFillText("x".repeat(2_049)) }
        assertFailsWith<IllegalArgumentException> { DeviceActionPayload.UiSelectOption("\n") }
    }

    @Test
    fun payload_kind_mismatch_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            proposal(kind = DeviceActionKind.UI_FILL_TEXT, payload = DeviceActionPayload.UiClick)
        }
    }

    @Test
    fun confirmation_receipt_binds_every_authority_identity() {
        val proposal = proposal()
        val receipt = receipt(proposal)
        assertTrue(receipt.matches(proposal, 1_500))
        assertFalse(receipt.copy(payloadDigestSha256 = "b".repeat(64)).matches(proposal, 1_500))
        assertFalse(receipt.copy(policyVersion = "policy-v2").matches(proposal, 1_500))
        assertFalse(receipt.matches(proposal, 3_001))
    }

    @Test
    fun post_dispatch_timeout_cannot_claim_none_without_verifier_evidence() {
        assertFailsWith<IllegalArgumentException> {
            DeviceActionResult.TimedOut(
                proposalId = "proposal-1",
                dispatched = true,
                effectState = DeviceEffectState.NONE,
                evidence = null,
            )
        }
        val evidence = evidence(DeviceVerifierOutcome.NO_EFFECT)
        val result = DeviceActionResult.TimedOut(
            proposalId = "proposal-1",
            dispatched = true,
            effectState = DeviceEffectState.NONE,
            evidence = evidence,
        )
        assertEquals(DeviceEffectState.NONE, result.effectState)
    }

    @Test
    fun dispatched_is_not_success_and_verified_results_require_matching_evidence() {
        val dispatched = DeviceActionResult.DispatchedAwaitingVerification("proposal-1", "dispatch-1")
        val encoded = json.encodeToString(DeviceActionResult.serializer(), dispatched)
        assertTrue(encoded.contains("dispatched_awaiting_verification"))
        assertFailsWith<IllegalArgumentException> {
            DeviceActionResult.VerifiedApplied("proposal-1", evidence(DeviceVerifierOutcome.NO_EFFECT))
        }
    }

    private fun proposal(
        kind: DeviceActionKind = DeviceActionKind.UI_CLICK,
        payload: DeviceActionPayload = DeviceActionPayload.UiClick,
    ) = DeviceActionProposal(
        proposalId = "proposal-1",
        intentId = "intent-1",
        canonicalActionId = "own-webview.click",
        capabilityId = DeviceCapabilityId("own-webview-actions"),
        profile = DistributionProfile.PLAY_SAFE,
        subject = DeviceSubjectRef("dev.ed3c.autowebview", "window-1", "display-0", 7, 1_000),
        target = DeviceTargetRef.UiTarget("opaque-fingerprint-1", 7),
        kind = kind,
        payload = payload,
        payloadDigestSha256 = "a".repeat(64),
        risk = DeviceActionRisk.MEDIUM,
        createdAtEpochMs = 1_100,
        expiresAtEpochMs = 2_000,
        requiredPermissions = emptySet(),
        confirmationClass = DeviceConfirmationClass.USER_CONFIRMATION,
        verifierId = "webview-postcondition-v1",
        auditCategory = "device-action",
        policyVersion = "policy-v1",
    )

    private fun receipt(proposal: DeviceActionProposal) = DeviceConfirmationReceipt(
        receiptId = "receipt-1",
        proposalId = proposal.proposalId,
        canonicalActionId = proposal.canonicalActionId,
        capabilityId = proposal.capabilityId,
        profile = proposal.profile,
        subject = proposal.subject,
        target = proposal.target,
        payloadDigestSha256 = proposal.payloadDigestSha256,
        policyVersion = proposal.policyVersion,
        confirmedAtEpochMs = 1_200,
        expiresAtEpochMs = 3_000,
    )

    private fun evidence(outcome: DeviceVerifierOutcome) = DeviceVerificationEvidence(
        verifierId = "webview-postcondition-v1",
        subject = DeviceSubjectRef("dev.ed3c.autowebview", "window-1", "display-0", 7, 1_000),
        target = DeviceTargetRef.UiTarget("opaque-fingerprint-1", 7),
        observedAtEpochMs = 1_500,
        outcome = outcome,
        evidenceDigestSha256 = "c".repeat(64),
    )
}
