package dev.ed3c.autowebview.device.policy

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityDescriptor
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityScope
import dev.ed3c.autowebview.device.catalog.DevicePrivilegeClass
import dev.ed3c.autowebview.device.contract.DeviceActionProposal
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DistributionProfile {
    PLAY_SAFE,
    ENTERPRISE_SIDELOAD,
    ACCESSIBILITY_TOOL,
}

@Serializable
enum class DeviceActionRisk {
    READ_ONLY,
    LOW,
    MEDIUM,
    HIGH,
    DESTRUCTIVE,
}

@Serializable
enum class DevicePolicyDenialCode {
    PROFILE_MISMATCH,
    EXTERNAL_ADMISSION_REQUIRED,
    UNKNOWN_CAPABILITY,
    CAPABILITY_DISABLED,
    UNKNOWN_ACTION,
    ACTION_KIND_MISMATCH,
    PROFILE_CEILING,
    DEVICE_WIDE_NOT_PLAY_SAFE,
    PRIVILEGED_NOT_PLAY_SAFE,
    MISSING_PERMISSION,
    RISK_CEILING,
    UNKNOWN_VERIFIER,
    CONFIRMATION_CLASS_MISMATCH,
}

@Serializable
sealed interface DeviceActionPolicyDecision {
    @Serializable
    @SerialName("allowed")
    data object Allowed : DeviceActionPolicyDecision

    @Serializable
    @SerialName("requires_confirmation")
    data class RequiresConfirmation(
        val confirmationClass: DeviceConfirmationClass,
        val reason: String,
    ) : DeviceActionPolicyDecision

    @Serializable
    @SerialName("denied")
    data class Denied(
        val code: DevicePolicyDenialCode,
        val reason: String,
    ) : DeviceActionPolicyDecision
}

class DeviceActionPolicy(
    private val compiledProfile: DistributionProfile,
    descriptors: List<DeviceCapabilityDescriptor>,
    enabledCapabilityIds: Set<DeviceCapabilityId>,
    private val grantedPermissions: Set<String> = emptySet(),
    private val externalAccessibilityToolAdmission: Boolean = false,
) {
    private val descriptorsById = descriptors.associateBy(DeviceCapabilityDescriptor::id)
    private val enabledCapabilityIds = enabledCapabilityIds.toSet()

    init {
        require(descriptorsById.size == descriptors.size) { "Duplicate device capability descriptor" }
        require(enabledCapabilityIds.all(descriptorsById::containsKey)) { "Enabled capability is not registered" }
        require(grantedPermissions.size <= 64) { "Granted permission set is unbounded" }
        if (compiledProfile != DistributionProfile.ACCESSIBILITY_TOOL) {
            require(!externalAccessibilityToolAdmission) {
                "External accessibility-tool admission cannot widen a different compiled profile"
            }
        }
    }

    fun evaluate(proposal: DeviceActionProposal): DeviceActionPolicyDecision {
        if (proposal.profile != compiledProfile) {
            return denied(DevicePolicyDenialCode.PROFILE_MISMATCH, "Proposal profile differs from compiled profile")
        }
        if (compiledProfile == DistributionProfile.ACCESSIBILITY_TOOL && !externalAccessibilityToolAdmission) {
            return denied(DevicePolicyDenialCode.EXTERNAL_ADMISSION_REQUIRED, "Accessibility-tool profile requires external admission")
        }
        val descriptor = descriptorsById[proposal.capabilityId]
            ?: return denied(DevicePolicyDenialCode.UNKNOWN_CAPABILITY, "Capability is not registered")
        if (proposal.capabilityId !in enabledCapabilityIds) {
            return denied(DevicePolicyDenialCode.CAPABILITY_DISABLED, "Capability is disabled")
        }
        if (proposal.canonicalActionId !in descriptor.canonicalActionIds) {
            return denied(DevicePolicyDenialCode.UNKNOWN_ACTION, "Canonical action is not admitted by the capability")
        }
        if (proposal.kind !in descriptor.actionKinds) {
            return denied(DevicePolicyDenialCode.ACTION_KIND_MISMATCH, "Typed action kind is not admitted by the capability")
        }
        if (compiledProfile !in descriptor.allowedProfiles) {
            return denied(DevicePolicyDenialCode.PROFILE_CEILING, "Compiled profile is outside the capability ceiling")
        }
        if (descriptor.externallyAdmittedOnly && !externalAccessibilityToolAdmission) {
            return denied(DevicePolicyDenialCode.EXTERNAL_ADMISSION_REQUIRED, "Capability requires external admission")
        }
        if (compiledProfile == DistributionProfile.PLAY_SAFE && descriptor.scope != DeviceCapabilityScope.OWN_WEBVIEW) {
            return denied(DevicePolicyDenialCode.DEVICE_WIDE_NOT_PLAY_SAFE, "PLAY_SAFE excludes device-wide authority")
        }
        if (compiledProfile == DistributionProfile.PLAY_SAFE && descriptor.privilegeClass != DevicePrivilegeClass.NONE) {
            return denied(DevicePolicyDenialCode.PRIVILEGED_NOT_PLAY_SAFE, "PLAY_SAFE excludes privileged execution")
        }
        val missingPermissions = (descriptor.requiredPermissions + proposal.requiredPermissions) - grantedPermissions
        if (missingPermissions.isNotEmpty()) {
            return denied(DevicePolicyDenialCode.MISSING_PERMISSION, "Required permissions are not granted")
        }
        if (proposal.risk.ordinal > descriptor.maximumRisk.ordinal) {
            return denied(DevicePolicyDenialCode.RISK_CEILING, "Action exceeds capability risk ceiling")
        }
        if (proposal.verifierId != descriptor.verifierId) {
            return denied(DevicePolicyDenialCode.UNKNOWN_VERIFIER, "Verifier identity is not admitted by the capability")
        }
        if (proposal.confirmationClass != descriptor.confirmationClass) {
            return denied(DevicePolicyDenialCode.CONFIRMATION_CLASS_MISMATCH, "Confirmation class differs from capability contract")
        }
        return if (descriptor.confirmationClass == DeviceConfirmationClass.NONE && proposal.risk == DeviceActionRisk.READ_ONLY) {
            DeviceActionPolicyDecision.Allowed
        } else {
            DeviceActionPolicyDecision.RequiresConfirmation(
                confirmationClass = descriptor.confirmationClass,
                reason = "State-changing device actions require the configured human or external confirmation",
            )
        }
    }

    private fun denied(code: DevicePolicyDenialCode, reason: String) =
        DeviceActionPolicyDecision.Denied(code = code, reason = reason)
}
