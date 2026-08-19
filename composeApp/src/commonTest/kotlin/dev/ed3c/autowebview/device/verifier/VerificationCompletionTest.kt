package dev.ed3c.autowebview.device.verifier

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityDescriptor
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityScope
import dev.ed3c.autowebview.device.catalog.DevicePrivilegeClass
import dev.ed3c.autowebview.device.contract.DeviceActionKind
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DistributionProfile
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VerificationCompletionTest {
    @Test
    fun deadline_observer_loss_and_contradiction_are_unknown() {
        val plan = VerificationPlan(
            key = key(),
            maximumObservationAgeMs = 5_000,
            maximumVerificationDurationMs = 250,
        )
        val registry = VerificationRegistry(listOf(plan))
        val deadline = registry.verify(
            key(),
            pre(1_000),
            post(PostconditionObservation.TRUE, 1_251),
            1_251,
        )
        assertEquals(VerificationVerdictCode.DEADLINE_EXCEEDED, deadline.code)
        assertEquals(DeviceEffectState.UNKNOWN, deadline.effectState)
        assertEquals(
            VerificationVerdictCode.OBSERVER_LOST,
            registry.verify(key(), pre(1_000), post(PostconditionObservation.OBSERVER_LOST, 1_100), 1_100).code,
        )
        assertEquals(
            VerificationVerdictCode.CONTRADICTORY_EVIDENCE,
            registry.verify(key(), pre(1_000), post(PostconditionObservation.CONTRADICTORY, 1_100), 1_100).code,
        )
    }

    @Test
    fun generation_verifier_version_and_proposal_mutations_fail_identity_binding() {
        val registry = VerificationRegistry(listOf(VerificationPlan(key(), 5_000)))
        val wrongGeneration = post(PostconditionObservation.TRUE, 1_100).copy(
            identity = identity().copy(
                subject = identity().subject.copy(snapshotVersion = 8),
                target = DeviceTargetRef.UiTarget("opaque-target-1", 8),
            ),
        )
        assertEquals(
            VerificationVerdictCode.IDENTITY_MISMATCH,
            registry.verify(key(), pre(1_000), wrongGeneration, 1_100).code,
        )
        val wrongVersion = post(PostconditionObservation.TRUE, 1_100).copy(
            identity = identity().copy(verifierVersion = 2),
        )
        assertEquals(
            VerificationVerdictCode.IDENTITY_MISMATCH,
            registry.verify(key(), pre(1_000), wrongVersion, 1_100).code,
        )
        val wrongProposal = post(PostconditionObservation.TRUE, 1_100).copy(
            identity = identity().copy(proposalId = "proposal-other"),
        )
        assertEquals(
            VerificationVerdictCode.IDENTITY_MISMATCH,
            registry.verify(key(), pre(1_000), wrongProposal, 1_100).code,
        )
    }

    @Test
    fun coverage_oracle_requires_exactly_one_current_verifier_for_each_state_changing_action() {
        val descriptor = descriptor()
        val oracle = VerificationCoverageOracle()
        assertEquals(
            VerificationCoverageCode.MISSING_CURRENT_PLAN,
            oracle.evaluate(listOf(descriptor), emptyList()).code,
        )
        assertEquals(
            VerificationCoverageCode.COMPLETE,
            oracle.evaluate(listOf(descriptor), listOf(VerificationPlan(key(), 5_000))).code,
        )
        val duplicateCurrent = VerificationPlan(key().copy(verifierVersion = 2), 5_000)
        assertEquals(
            VerificationCoverageCode.MULTIPLE_CURRENT_PLANS,
            oracle.evaluate(
                listOf(descriptor),
                listOf(VerificationPlan(key(), 5_000), duplicateCurrent),
            ).code,
        )
        val historical = VerificationPlan(key().copy(verifierVersion = 2), 5_000, current = false)
        assertEquals(
            VerificationCoverageCode.COMPLETE,
            oracle.evaluate(
                listOf(descriptor),
                listOf(VerificationPlan(key(), 5_000), historical),
            ).code,
        )
    }

    @Test
    fun evidence_model_serializes_digests_only_not_raw_sensitive_values() {
        val evidence = post(PostconditionObservation.TRUE, 1_100).copy(
            privacyClass = VerificationPrivacyClass.SENSITIVE_DIGEST,
        )
        val encoded = Json.encodeToString(PostconditionEvidence.serializer(), evidence)
        assertFalse(encoded.contains("hunter2"))
        assertFalse(encoded.contains("rawValue"))
        assertFalse(encoded.contains("exception"))
    }

    private fun descriptor() = DeviceCapabilityDescriptor(
        id = key().capabilityId,
        canonicalActionIds = setOf(key().canonicalActionId),
        actionKinds = setOf(DeviceActionKind.UI_CLICK),
        allowedProfiles = setOf(DistributionProfile.PLAY_SAFE),
        scope = DeviceCapabilityScope.OWN_WEBVIEW,
        privilegeClass = DevicePrivilegeClass.NONE,
        maximumRisk = DeviceActionRisk.MEDIUM,
        confirmationClass = DeviceConfirmationClass.USER_CONFIRMATION,
        verifierId = key().verifierId,
        auditCategory = "device-action",
    )

    private fun key() = VerificationPlanKey(
        capabilityId = DeviceCapabilityId("own-webview-actions"),
        canonicalActionId = "own-webview.click",
        verifierId = "webview-postcondition-v1",
        verifierVersion = 1,
    )

    private fun identity() = VerificationIdentity(
        proposalId = "proposal-1",
        capabilityId = key().capabilityId,
        canonicalActionId = key().canonicalActionId,
        subject = DeviceSubjectRef("dev.ed3c.autowebview", "window-1", "display-0", 7, 900),
        target = DeviceTargetRef.UiTarget("opaque-target-1", 7),
        verifierId = key().verifierId,
        verifierVersion = key().verifierVersion,
    )

    private fun pre(at: Long) = PreconditionEvidence(
        identity = identity(),
        observedAtEpochMs = at,
        evidenceDigestSha256 = "a".repeat(64),
        source = VerificationEvidenceSource.SANITIZED_UI,
        privacyClass = VerificationPrivacyClass.SANITIZED_DIGEST,
    )

    private fun post(observation: PostconditionObservation, at: Long) = PostconditionEvidence(
        identity = identity(),
        observedAtEpochMs = at,
        evidenceDigestSha256 = "b".repeat(64),
        source = VerificationEvidenceSource.SANITIZED_UI,
        privacyClass = VerificationPrivacyClass.SANITIZED_DIGEST,
        observation = observation,
    )
}
