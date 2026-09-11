package dev.ed3c.autowebview.device.verifier

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import kotlin.test.Test
import kotlin.test.assertEquals

class VerificationRegistryTest {
    @Test
    fun true_false_and_inconclusive_postconditions_map_to_honest_effect_states() {
        val registry = registry()
        assertEquals(DeviceEffectState.APPLIED, registry.verify(key(), pre(), post(PostconditionObservation.TRUE), 1_600).effectState)
        assertEquals(DeviceEffectState.NONE, registry.verify(key(), pre(), post(PostconditionObservation.FALSE), 1_600).effectState)
        val unknown = registry.verify(key(), pre(), post(PostconditionObservation.INCONCLUSIVE), 1_600)
        assertEquals(DeviceEffectState.UNKNOWN, unknown.effectState)
        assertEquals(true, unknown.reconciliationRequired)
    }

    @Test
    fun wrong_identity_or_stale_evidence_fails_closed_to_unknown() {
        val registry = registry()
        val wrongIdentity = post(PostconditionObservation.TRUE).copy(
            identity = identity().copy(proposalId = "proposal-other"),
        )
        assertEquals(
            VerificationVerdictCode.IDENTITY_MISMATCH,
            registry.verify(key(), pre(), wrongIdentity, 1_600).code,
        )
        assertEquals(
            VerificationVerdictCode.STALE_EVIDENCE,
            registry.verify(key(), pre(), post(PostconditionObservation.TRUE), 10_000).code,
        )
    }

    @Test
    fun missing_verifier_plan_never_defaults_to_success() {
        val unknownKey = key().copy(verifierVersion = 2)
        val verdict = registry().verify(unknownKey, pre(), post(PostconditionObservation.TRUE), 1_600)
        assertEquals(VerificationVerdictCode.UNKNOWN_PLAN, verdict.code)
        assertEquals(DeviceEffectState.UNKNOWN, verdict.effectState)
    }

    private fun registry() = VerificationRegistry(listOf(VerificationPlan(key(), maximumObservationAgeMs = 5_000)))

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
        subject = DeviceSubjectRef("dev.ed3c.autowebview", "window-1", "display-0", 7, 1_000),
        target = DeviceTargetRef.UiTarget("opaque-target-1", 7),
        verifierId = key().verifierId,
        verifierVersion = key().verifierVersion,
    )

    private fun pre() = PreconditionEvidence(
        identity = identity(),
        observedAtEpochMs = 1_200,
        evidenceDigestSha256 = "a".repeat(64),
        source = VerificationEvidenceSource.SANITIZED_UI,
        privacyClass = VerificationPrivacyClass.SANITIZED_DIGEST,
    )

    private fun post(observation: PostconditionObservation) = PostconditionEvidence(
        identity = identity(),
        observedAtEpochMs = 1_500,
        evidenceDigestSha256 = "b".repeat(64),
        source = VerificationEvidenceSource.SANITIZED_UI,
        privacyClass = VerificationPrivacyClass.SANITIZED_DIGEST,
        observation = observation,
    )
}
