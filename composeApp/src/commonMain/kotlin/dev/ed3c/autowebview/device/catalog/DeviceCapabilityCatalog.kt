package dev.ed3c.autowebview.device.catalog

import dev.ed3c.autowebview.device.contract.DeviceActionKind
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DistributionProfile
import kotlinx.serialization.Serializable

@Serializable
data class DeviceCapabilityId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            "Capability id must be a bounded canonical identifier"
        }
    }

    override fun toString(): String = value
}

@Serializable
enum class DeviceCapabilityScope {
    OWN_WEBVIEW,
    DEVICE_WIDE_ACCESSIBILITY,
    DEVICE_RESOURCE,
}

@Serializable
enum class DevicePrivilegeClass {
    NONE,
    ACCESSIBILITY,
    SHIZUKU_TYPED,
}

@Serializable
data class DeviceCapabilityDescriptor(
    val id: DeviceCapabilityId,
    val canonicalActionIds: Set<String>,
    val actionKinds: Set<DeviceActionKind>,
    val allowedProfiles: Set<DistributionProfile>,
    val scope: DeviceCapabilityScope,
    val privilegeClass: DevicePrivilegeClass,
    val maximumRisk: DeviceActionRisk,
    val requiredPermissions: Set<String> = emptySet(),
    val confirmationClass: DeviceConfirmationClass,
    val verifierId: String,
    val auditCategory: String,
    val externallyAdmittedOnly: Boolean = false,
) {
    init {
        require(canonicalActionIds.isNotEmpty()) { "Capability must expose at least one canonical action" }
        require(actionKinds.isNotEmpty()) { "Capability must expose at least one typed action kind" }
        require(allowedProfiles.isNotEmpty()) { "Capability must name an explicit compiled profile" }
        canonicalActionIds.forEach {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Invalid canonical action id" }
        }
        require(canonicalActionIds.size <= 32 && actionKinds.size <= 16 && allowedProfiles.size <= 3) { "Capability lists are unbounded" }
        require(requiredPermissions.size <= 32) { "Capability permission list is unbounded" }
        requiredPermissions.forEach {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Invalid permission id" }
        }
        require(verifierId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Invalid verifier id" }
        require(auditCategory.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Invalid audit category" }
        if (externallyAdmittedOnly) {
            require(DistributionProfile.ACCESSIBILITY_TOOL in allowedProfiles) {
                "External-only capability must be bound to ACCESSIBILITY_TOOL"
            }
        }
        if (DistributionProfile.PLAY_SAFE in allowedProfiles) {
            require(scope == DeviceCapabilityScope.OWN_WEBVIEW) { "PLAY_SAFE cannot admit device-wide scope" }
            require(privilegeClass == DevicePrivilegeClass.NONE) { "PLAY_SAFE cannot admit privileged execution" }
        }
    }
}

@Serializable
enum class CapabilityAdmissionDecision {
    ADOPT_AS_CONTRACT,
    ADAPT_BEHIND_POLICY,
    REFERENCE_ONLY,
    DENIED_BY_ARCHITECTURE,
    EXTERNAL_POLICY_ADMIT_REQUIRED,
}

@Serializable
enum class CompatibilityProfileCeiling {
    NONE,
    ENTERPRISE_SIDELOAD,
}

@Serializable
data class OpenDroidCompatibilityRecord(
    val id: DeviceCapabilityId,
    val sourcePath: String,
    val sourceBlobSha: String,
    val decision: CapabilityAdmissionDecision,
    val profileCeiling: CompatibilityProfileCeiling,
    val contractOwner: String,
    val negativeControl: String,
    val evidenceLane: String,
) {
    init {
        require(sourcePath.isNotBlank() && sourcePath.length <= 512 && sourcePath.none(Char::isISOControl)) {
            "Compatibility source path is invalid"
        }
        require(sourceBlobSha.matches(Regex("[0-9a-f]{40}"))) { "Compatibility source must bind an exact blob SHA" }
        require(contractOwner.isNotBlank() && contractOwner.length <= 128) { "Compatibility contract owner is invalid" }
        require(negativeControl.isNotBlank() && negativeControl.length <= 128) { "Compatibility negative control is invalid" }
        require(evidenceLane.matches(Regex("[A-Z_]{3,32}"))) { "Compatibility evidence lane is invalid" }
    }

    val executableCandidate: Boolean
        get() = decision == CapabilityAdmissionDecision.ADOPT_AS_CONTRACT ||
            decision == CapabilityAdmissionDecision.ADAPT_BEHIND_POLICY
}

object OpenDroidCompatibilityCatalog {
    const val UPSTREAM_COMMIT = "0e9e5898f0e0dcc679d99e5f4518e19310e96775"
    const val UPSTREAM_TREE = "4c9d1d5f644fc69d9a0a5e658b51d1753fd2ac32"

    val records: List<OpenDroidCompatibilityRecord> = listOf(
        record("action-taxonomy", "app/src/main/java/com/opendroid/ai/actions/ActionDispatcher.kt", "60287ce616911f4b59de1ecb523d27a8bdecae8a", CapabilityAdmissionDecision.ADAPT_BEHIND_POLICY, CompatibilityProfileCeiling.ENTERPRISE_SIDELOAD, "issue-66", "raw-alias-cannot-dispatch", "STATIC"),
        record("action-auto-mapping", "app/src/main/java/com/opendroid/ai/actions/ActionAutoMapper.kt", "59ba61a2b136f1eb626c271e794706ab66fffd63", CapabilityAdmissionDecision.REFERENCE_ONLY, CompatibilityProfileCeiling.NONE, "issue-69", "semantic-alias-must-not-widen-capability", "STATIC"),
        record("accessibility-observation", "app/src/main/java/com/opendroid/ai/accessibility/AccessibilityNodeTraversal.kt", "02fe4abcaaa76491cb67fcfec60d650ae8fbf311", CapabilityAdmissionDecision.ADAPT_BEHIND_POLICY, CompatibilityProfileCeiling.ENTERPRISE_SIDELOAD, "issue-67", "stale-or-ambiguous-target-denied", "EMULATOR"),
        record("accessibility-action-service", "app/src/main/java/com/opendroid/ai/accessibility/OpenDroidAccessibilityService.kt", "99f0e28dce230e870d0551be95a61952e11ff985", CapabilityAdmissionDecision.ADAPT_BEHIND_POLICY, CompatibilityProfileCeiling.ENTERPRISE_SIDELOAD, "issue-73", "play-safe-artifact-must-not-package-service", "PHYSICAL"),
        record("generic-app-automation", "app/src/main/java/com/opendroid/ai/accessibility/GenericAppAutomator.kt", "7825d9ec738522182a7babf136533e9f0cf05faf", CapabilityAdmissionDecision.REFERENCE_ONLY, CompatibilityProfileCeiling.NONE, "issue-67", "first-text-match-denied", "STATIC"),
        record("postcondition-verification-pattern", "app/src/main/java/com/opendroid/ai/accessibility/CallFlowVerifier.kt", "e7cfe5bc3089834f543a0f8bd415647921bd37bb", CapabilityAdmissionDecision.ADOPT_AS_CONTRACT, CompatibilityProfileCeiling.ENTERPRISE_SIDELOAD, "issue-68", "dispatch-success-without-postcondition-is-UNKNOWN", "LOCAL"),
        record("multi-step-call-flow", "app/src/main/java/com/opendroid/ai/actions/CallFlowExecutor.kt", "e933602c28ff108f8ef82b8fdc1d7c97f4474909", CapabilityAdmissionDecision.ADAPT_BEHIND_POLICY, CompatibilityProfileCeiling.ENTERPRISE_SIDELOAD, "issue-69", "string-interpolation-cannot-cross-sensitive-field", "LOCAL"),
        record("sms-and-communications", "app/src/main/java/com/opendroid/ai/actions/CommunicationActions.kt", "7c5f44a5b3d107891e4646cc2dc277ad7b40a57b", CapabilityAdmissionDecision.EXTERNAL_POLICY_ADMIT_REQUIRED, CompatibilityProfileCeiling.ENTERPRISE_SIDELOAD, "future-capability-leaf", "no-generic-send-capability", "PHYSICAL"),
        record("calendar-actions", "app/src/main/java/com/opendroid/ai/actions/CalendarActions.kt", "b885606ca317bc82a4551cbb3b5e286e243adf75", CapabilityAdmissionDecision.EXTERNAL_POLICY_ADMIT_REQUIRED, CompatibilityProfileCeiling.ENTERPRISE_SIDELOAD, "future-capability-leaf", "no-broad-calendar-authority", "PHYSICAL"),
        record("raw-coordinate-or-gesture-authority", "app/src/main/java/com/opendroid/ai/actions/AdvancedControlActions.kt", "a7c5b663b6d6666b9d132c80f96d5b8f76e7965a", CapabilityAdmissionDecision.DENIED_BY_ARCHITECTURE, CompatibilityProfileCeiling.NONE, "none", "model-generated-coordinate-rejected", "STATIC"),
        record("direct-mcp-execution", "README.md", "e3fec9627be574d7b2e2767718ce0a2c99c0a346", CapabilityAdmissionDecision.DENIED_BY_ARCHITECTURE, CompatibilityProfileCeiling.NONE, "none", "inbound-mobile-execute-action-absent", "STATIC"),
        record("privileged-shell-root-terminal", "README.md", "e3fec9627be574d7b2e2767718ce0a2c99c0a346", CapabilityAdmissionDecision.DENIED_BY_ARCHITECTURE, CompatibilityProfileCeiling.NONE, "none", "caller-supplied-command-string-rejected", "STATIC"),
    )

    private fun record(
        id: String,
        sourcePath: String,
        blob: String,
        decision: CapabilityAdmissionDecision,
        ceiling: CompatibilityProfileCeiling,
        owner: String,
        negativeControl: String,
        evidenceLane: String,
    ) = OpenDroidCompatibilityRecord(
        id = DeviceCapabilityId(id),
        sourcePath = sourcePath,
        sourceBlobSha = blob,
        decision = decision,
        profileCeiling = ceiling,
        contractOwner = owner,
        negativeControl = negativeControl,
        evidenceLane = evidenceLane,
    )
}
