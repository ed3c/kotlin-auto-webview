package dev.ed3c.autowebview.device.verifier

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityDescriptor
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import kotlinx.serialization.Serializable

@Serializable
enum class VerificationCoverageCode {
    COMPLETE,
    MISSING_CURRENT_PLAN,
    MULTIPLE_CURRENT_PLANS,
    VERIFIER_ID_MISMATCH,
}

@Serializable
data class VerificationCoverageResult(
    val code: VerificationCoverageCode,
    val capabilityId: String? = null,
    val canonicalActionId: String? = null,
) {
    val complete: Boolean get() = code == VerificationCoverageCode.COMPLETE
}

class VerificationCoverageOracle {
    fun evaluate(
        descriptors: List<DeviceCapabilityDescriptor>,
        plans: List<VerificationPlan>,
    ): VerificationCoverageResult {
        val stateChanging = descriptors.filter { it.maximumRisk != DeviceActionRisk.READ_ONLY }
        for (descriptor in stateChanging.sortedBy { it.id.value }) {
            for (actionId in descriptor.canonicalActionIds.sorted()) {
                val current = plans.filter {
                    it.current &&
                        it.key.capabilityId == descriptor.id &&
                        it.key.canonicalActionId == actionId
                }
                if (current.isEmpty()) {
                    return result(VerificationCoverageCode.MISSING_CURRENT_PLAN, descriptor, actionId)
                }
                if (current.size > 1) {
                    return result(VerificationCoverageCode.MULTIPLE_CURRENT_PLANS, descriptor, actionId)
                }
                if (current.single().key.verifierId != descriptor.verifierId) {
                    return result(VerificationCoverageCode.VERIFIER_ID_MISMATCH, descriptor, actionId)
                }
            }
        }
        return VerificationCoverageResult(VerificationCoverageCode.COMPLETE)
    }

    private fun result(
        code: VerificationCoverageCode,
        descriptor: DeviceCapabilityDescriptor,
        actionId: String,
    ) = VerificationCoverageResult(
        code = code,
        capabilityId = descriptor.id.value,
        canonicalActionId = actionId,
    )
}
