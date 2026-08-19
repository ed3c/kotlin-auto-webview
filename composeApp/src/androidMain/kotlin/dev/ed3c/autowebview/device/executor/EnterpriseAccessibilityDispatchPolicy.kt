package dev.ed3c.autowebview.device.executor

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityDescriptor
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityScope
import dev.ed3c.autowebview.device.catalog.DevicePrivilegeClass
import dev.ed3c.autowebview.device.contract.DeviceActionCommand
import dev.ed3c.autowebview.device.contract.DeviceActionKind
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DistributionProfile
import dev.ed3c.autowebview.device.verifier.VerificationPlan
import dev.ed3c.autowebview.device.verifier.VerificationPlanKey

object EnterpriseAccessibilityClickContract {
    const val CAPABILITY_ID = "enterprise.accessibility.click-exact-target"
    const val CANONICAL_ACTION_ID = "ui.click.exact-target"
    const val VERIFIER_ID = "enterprise.accessibility.click.postcondition"
    const val VERIFIER_VERSION = 1

    val capabilityId: DeviceCapabilityId = DeviceCapabilityId(CAPABILITY_ID)

    fun descriptor(): DeviceCapabilityDescriptor = DeviceCapabilityDescriptor(
        id = capabilityId,
        canonicalActionIds = setOf(CANONICAL_ACTION_ID),
        actionKinds = setOf(DeviceActionKind.UI_CLICK),
        allowedProfiles = setOf(DistributionProfile.ENTERPRISE_SIDELOAD),
        scope = DeviceCapabilityScope.DEVICE_WIDE_ACCESSIBILITY,
        privilegeClass = DevicePrivilegeClass.ACCESSIBILITY,
        maximumRisk = DeviceActionRisk.HIGH,
        requiredPermissions = emptySet(),
        confirmationClass = DeviceConfirmationClass.USER_CONFIRMATION,
        verifierId = VERIFIER_ID,
        auditCategory = "enterprise-accessibility-click",
    )

    fun verificationPlan(): VerificationPlan = VerificationPlan(
        key = VerificationPlanKey(
            capabilityId = capabilityId,
            canonicalActionId = CANONICAL_ACTION_ID,
            verifierId = VERIFIER_ID,
            verifierVersion = VERIFIER_VERSION,
        ),
        maximumObservationAgeMs = 5_000,
        maximumVerificationDurationMs = 15_000,
        requiresIndependentPostcondition = true,
    )
}

enum class EnterpriseAccessibilityDispatchDecision {
    ADMITTED,
    WRONG_PROFILE,
    WRONG_CAPABILITY,
    WRONG_CANONICAL_ACTION,
    UNSUPPORTED_ACTION_KIND,
    WRONG_TARGET_KIND,
    CONFIRMATION_MISSING,
}

class EnterpriseAccessibilityDispatchPolicy {
    fun evaluate(command: DeviceActionCommand): EnterpriseAccessibilityDispatchDecision {
        if (command.profile != DistributionProfile.ENTERPRISE_SIDELOAD) {
            return EnterpriseAccessibilityDispatchDecision.WRONG_PROFILE
        }
        if (command.capabilityId != EnterpriseAccessibilityClickContract.capabilityId) {
            return EnterpriseAccessibilityDispatchDecision.WRONG_CAPABILITY
        }
        if (command.canonicalActionId != EnterpriseAccessibilityClickContract.CANONICAL_ACTION_ID) {
            return EnterpriseAccessibilityDispatchDecision.WRONG_CANONICAL_ACTION
        }
        if (command.kind != DeviceActionKind.UI_CLICK) {
            return EnterpriseAccessibilityDispatchDecision.UNSUPPORTED_ACTION_KIND
        }
        if (command.target !is DeviceTargetRef.UiTarget) {
            return EnterpriseAccessibilityDispatchDecision.WRONG_TARGET_KIND
        }
        if (command.confirmationReceiptId.isNullOrBlank()) {
            return EnterpriseAccessibilityDispatchDecision.CONFIRMATION_MISSING
        }
        return EnterpriseAccessibilityDispatchDecision.ADMITTED
    }
}
