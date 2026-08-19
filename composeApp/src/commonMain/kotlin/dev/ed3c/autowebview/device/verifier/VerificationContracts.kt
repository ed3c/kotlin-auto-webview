package dev.ed3c.autowebview.device.verifier

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import kotlinx.serialization.Serializable

@Serializable
data class VerificationPlanKey(
    val capabilityId: DeviceCapabilityId,
    val canonicalActionId: String,
    val verifierId: String,
    val verifierVersion: Int,
) {
    init {
        requireCanonical(canonicalActionId, "canonical action id")
        requireCanonical(verifierId, "verifier id")
        require(verifierVersion > 0) { "Verifier version must be positive" }
    }
}

@Serializable
enum class VerificationEvidenceSource {
    PLATFORM_ADAPTER,
    SANITIZED_UI,
    SYSTEM_QUERY,
    API_QUERY,
    FIXTURE,
}

@Serializable
enum class VerificationPrivacyClass {
    PUBLIC_METADATA,
    SANITIZED_DIGEST,
    SENSITIVE_DIGEST,
}

@Serializable
data class VerificationIdentity(
    val proposalId: String,
    val capabilityId: DeviceCapabilityId,
    val canonicalActionId: String,
    val subject: DeviceSubjectRef,
    val target: DeviceTargetRef,
    val verifierId: String,
    val verifierVersion: Int,
) {
    init {
        requireCanonical(proposalId, "proposal id")
        requireCanonical(canonicalActionId, "canonical action id")
        requireCanonical(verifierId, "verifier id")
        require(verifierVersion > 0) { "Verifier version must be positive" }
    }

    fun planKey(): VerificationPlanKey = VerificationPlanKey(
        capabilityId = capabilityId,
        canonicalActionId = canonicalActionId,
        verifierId = verifierId,
        verifierVersion = verifierVersion,
    )
}

@Serializable
data class PreconditionEvidence(
    val identity: VerificationIdentity,
    val observedAtEpochMs: Long,
    val evidenceDigestSha256: String,
    val source: VerificationEvidenceSource,
    val privacyClass: VerificationPrivacyClass,
) {
    init {
        require(observedAtEpochMs >= 0) { "Precondition timestamp cannot be negative" }
        requireSha256(evidenceDigestSha256, "precondition evidence digest")
    }
}

@Serializable
enum class PostconditionObservation {
    TRUE,
    FALSE,
    INCONCLUSIVE,
    CONTRADICTORY,
    OBSERVER_LOST,
}

@Serializable
data class PostconditionEvidence(
    val identity: VerificationIdentity,
    val observedAtEpochMs: Long,
    val evidenceDigestSha256: String,
    val source: VerificationEvidenceSource,
    val privacyClass: VerificationPrivacyClass,
    val observation: PostconditionObservation,
) {
    init {
        require(observedAtEpochMs >= 0) { "Postcondition timestamp cannot be negative" }
        requireSha256(evidenceDigestSha256, "postcondition evidence digest")
    }
}

@Serializable
data class VerificationPlan(
    val key: VerificationPlanKey,
    val maximumObservationAgeMs: Long,
    val requiresIndependentPostcondition: Boolean = true,
) {
    init {
        require(maximumObservationAgeMs in 0..120_000) { "Verification observation age is outside the bounded range" }
    }
}

@Serializable
enum class VerificationVerdictCode {
    APPLIED,
    NO_EFFECT,
    INCONCLUSIVE,
    CONTRADICTORY_EVIDENCE,
    OBSERVER_LOST,
    UNKNOWN_PLAN,
    IDENTITY_MISMATCH,
    STALE_EVIDENCE,
    INVALID_TIME_ORDER,
}

@Serializable
data class VerificationVerdict(
    val code: VerificationVerdictCode,
    val effectState: DeviceEffectState,
    val reconciliationRequired: Boolean,
    val evidenceDigestSha256: String? = null,
) {
    init {
        evidenceDigestSha256?.let { requireSha256(it, "verification verdict digest") }
        if (effectState == DeviceEffectState.UNKNOWN) {
            require(reconciliationRequired) { "UNKNOWN effect requires reconciliation" }
        }
        if (code == VerificationVerdictCode.APPLIED) require(effectState == DeviceEffectState.APPLIED)
        if (code == VerificationVerdictCode.NO_EFFECT) require(effectState == DeviceEffectState.NONE)
    }
}

class VerificationRegistry(
    plans: List<VerificationPlan>,
) {
    private val plansByKey = plans.associateBy(VerificationPlan::key)

    init {
        require(plansByKey.size == plans.size) { "Duplicate verification plan key" }
    }

    fun plan(key: VerificationPlanKey): VerificationPlan? = plansByKey[key]

    fun verify(
        key: VerificationPlanKey,
        precondition: PreconditionEvidence,
        postcondition: PostconditionEvidence,
        nowEpochMs: Long,
    ): VerificationVerdict {
        val plan = plansByKey[key] ?: return unknown(VerificationVerdictCode.UNKNOWN_PLAN)
        if (precondition.identity.planKey() != key || postcondition.identity.planKey() != key) {
            return unknown(VerificationVerdictCode.IDENTITY_MISMATCH)
        }
        if (precondition.identity != postcondition.identity) {
            return unknown(VerificationVerdictCode.IDENTITY_MISMATCH)
        }
        if (postcondition.observedAtEpochMs < precondition.observedAtEpochMs) {
            return unknown(VerificationVerdictCode.INVALID_TIME_ORDER)
        }
        if (
            nowEpochMs < postcondition.observedAtEpochMs ||
            nowEpochMs - postcondition.observedAtEpochMs > plan.maximumObservationAgeMs
        ) {
            return unknown(VerificationVerdictCode.STALE_EVIDENCE)
        }
        return when (postcondition.observation) {
            PostconditionObservation.TRUE -> VerificationVerdict(
                code = VerificationVerdictCode.APPLIED,
                effectState = DeviceEffectState.APPLIED,
                reconciliationRequired = false,
                evidenceDigestSha256 = postcondition.evidenceDigestSha256,
            )
            PostconditionObservation.FALSE -> VerificationVerdict(
                code = VerificationVerdictCode.NO_EFFECT,
                effectState = DeviceEffectState.NONE,
                reconciliationRequired = false,
                evidenceDigestSha256 = postcondition.evidenceDigestSha256,
            )
            PostconditionObservation.INCONCLUSIVE -> unknown(
                VerificationVerdictCode.INCONCLUSIVE,
                postcondition.evidenceDigestSha256,
            )
            PostconditionObservation.CONTRADICTORY -> unknown(
                VerificationVerdictCode.CONTRADICTORY_EVIDENCE,
                postcondition.evidenceDigestSha256,
            )
            PostconditionObservation.OBSERVER_LOST -> unknown(
                VerificationVerdictCode.OBSERVER_LOST,
                postcondition.evidenceDigestSha256,
            )
        }
    }

    private fun unknown(code: VerificationVerdictCode, digest: String? = null) = VerificationVerdict(
        code = code,
        effectState = DeviceEffectState.UNKNOWN,
        reconciliationRequired = true,
        evidenceDigestSha256 = digest,
    )
}

private val canonicalIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val sha256Digest = Regex("[0-9a-f]{64}")

private fun requireCanonical(value: String, field: String) {
    require(canonicalIdentifier.matches(value)) { "$field must be a bounded canonical identifier" }
}

private fun requireSha256(value: String, field: String) {
    require(sha256Digest.matches(value)) { "$field must be a lowercase SHA-256 digest" }
}
