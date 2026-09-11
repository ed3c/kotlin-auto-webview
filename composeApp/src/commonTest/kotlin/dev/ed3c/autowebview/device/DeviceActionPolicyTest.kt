package dev.ed3c.autowebview.device

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityDescriptor
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityScope
import dev.ed3c.autowebview.device.catalog.DevicePrivilegeClass
import dev.ed3c.autowebview.device.contract.DeviceActionKind
import dev.ed3c.autowebview.device.contract.DeviceActionPayload
import dev.ed3c.autowebview.device.contract.DeviceActionProposal
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.policy.DeviceActionPolicy
import dev.ed3c.autowebview.device.policy.DeviceActionPolicyDecision
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DevicePolicyDenialCode
import dev.ed3c.autowebview.device.policy.DistributionProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DeviceActionPolicyTest {
    @Test
    fun play_safe_allows_only_own_webview_contract_and_requires_confirmation_for_change() {
        val descriptor = ownWebViewDescriptor()
        val decision = DeviceActionPolicy(
            compiledProfile = DistributionProfile.PLAY_SAFE,
            descriptors = listOf(descriptor),
            enabledCapabilityIds = setOf(descriptor.id),
        ).evaluate(proposal())
        assertIs<DeviceActionPolicyDecision.RequiresConfirmation>(decision)
    }

    @Test
    fun runtime_profile_value_cannot_widen_compiled_play_safe_profile() {
        val descriptor = ownWebViewDescriptor()
        val decision = DeviceActionPolicy(
            compiledProfile = DistributionProfile.PLAY_SAFE,
            descriptors = listOf(descriptor),
            enabledCapabilityIds = setOf(descriptor.id),
        ).evaluate(proposal(profile = DistributionProfile.ENTERPRISE_SIDELOAD))
        assertDenied(DevicePolicyDenialCode.PROFILE_MISMATCH, decision)
    }

    @Test
    fun unknown_action_and_verifier_fail_closed() {
        val descriptor = ownWebViewDescriptor()
        val policy = DeviceActionPolicy(
            compiledProfile = DistributionProfile.PLAY_SAFE,
            descriptors = listOf(descriptor),
            enabledCapabilityIds = setOf(descriptor.id),
        )
        assertDenied(DevicePolicyDenialCode.UNKNOWN_ACTION, policy.evaluate(proposal(actionId = "run-shell")))
        assertDenied(DevicePolicyDenialCode.UNKNOWN_VERIFIER, policy.evaluate(proposal(verifierId = "unknown-verifier")))
    }

    @Test
    fun play_safe_descriptor_constructor_rejects_device_wide_or_privileged_authority() {
        assertFailsWith<IllegalArgumentException> {
            ownWebViewDescriptor().copy(scope = DeviceCapabilityScope.DEVICE_WIDE_ACCESSIBILITY)
        }
        assertFailsWith<IllegalArgumentException> {
            ownWebViewDescriptor().copy(privilegeClass = DevicePrivilegeClass.SHIZUKU_TYPED)
        }
    }

    @Test
    fun accessibility_tool_requires_external_admission_and_cannot_self_select() {
        val id = DeviceCapabilityId("accessibility-tool-actions")
        val descriptor = DeviceCapabilityDescriptor(
            id = id,
            canonicalActionIds = setOf("accessibility.observe"),
            actionKinds = setOf(DeviceActionKind.OBSERVE_SANITIZED_UI),
            allowedProfiles = setOf(DistributionProfile.ACCESSIBILITY_TOOL),
            scope = DeviceCapabilityScope.DEVICE_WIDE_ACCESSIBILITY,
            privilegeClass = DevicePrivilegeClass.ACCESSIBILITY,
            maximumRisk = DeviceActionRisk.READ_ONLY,
            confirmationClass = DeviceConfirmationClass.EXTERNAL_ADMISSION,
            verifierId = "sanitized-snapshot-v1",
            auditCategory = "accessibility-observation",
            externallyAdmittedOnly = true,
        )
        val proposal = DeviceActionProposal(
            proposalId = "proposal-2",
            intentId = "intent-2",
            canonicalActionId = "accessibility.observe",
            capabilityId = id,
            profile = DistributionProfile.ACCESSIBILITY_TOOL,
            subject = DeviceSubjectRef("dev.ed3c.autowebview", "window-1", "display-0", 1, 10),
            target = DeviceTargetRef.ResourceTarget("sanitized-ui", "current-window"),
            kind = DeviceActionKind.OBSERVE_SANITIZED_UI,
            payload = DeviceActionPayload.ObserveSanitizedUi,
            payloadDigestSha256 = "a".repeat(64),
            risk = DeviceActionRisk.READ_ONLY,
            createdAtEpochMs = 20,
            expiresAtEpochMs = 100,
            confirmationClass = DeviceConfirmationClass.EXTERNAL_ADMISSION,
            verifierId = "sanitized-snapshot-v1",
            auditCategory = "accessibility-observation",
            policyVersion = "policy-v1",
        )
        val denied = DeviceActionPolicy(
            compiledProfile = DistributionProfile.ACCESSIBILITY_TOOL,
            descriptors = listOf(descriptor),
            enabledCapabilityIds = setOf(id),
            externalAccessibilityToolAdmission = false,
        ).evaluate(proposal)
        assertDenied(DevicePolicyDenialCode.EXTERNAL_ADMISSION_REQUIRED, denied)
    }

    private fun ownWebViewDescriptor() = DeviceCapabilityDescriptor(
        id = DeviceCapabilityId("own-webview-actions"),
        canonicalActionIds = setOf("own-webview.click", "own-webview.fill-text", "own-webview.select-option"),
        actionKinds = setOf(DeviceActionKind.UI_CLICK, DeviceActionKind.UI_FILL_TEXT, DeviceActionKind.UI_SELECT_OPTION),
        allowedProfiles = setOf(DistributionProfile.PLAY_SAFE, DistributionProfile.ENTERPRISE_SIDELOAD),
        scope = DeviceCapabilityScope.OWN_WEBVIEW,
        privilegeClass = DevicePrivilegeClass.NONE,
        maximumRisk = DeviceActionRisk.HIGH,
        confirmationClass = DeviceConfirmationClass.USER_CONFIRMATION,
        verifierId = "webview-postcondition-v1",
        auditCategory = "device-action",
    )

    private fun proposal(
        profile: DistributionProfile = DistributionProfile.PLAY_SAFE,
        actionId: String = "own-webview.click",
        verifierId: String = "webview-postcondition-v1",
    ) = DeviceActionProposal(
        proposalId = "proposal-1",
        intentId = "intent-1",
        canonicalActionId = actionId,
        capabilityId = DeviceCapabilityId("own-webview-actions"),
        profile = profile,
        subject = DeviceSubjectRef("dev.ed3c.autowebview", "window-1", "display-0", 7, 1_000),
        target = DeviceTargetRef.UiTarget("opaque-fingerprint-1", 7),
        kind = DeviceActionKind.UI_CLICK,
        payload = DeviceActionPayload.UiClick,
        payloadDigestSha256 = "a".repeat(64),
        risk = DeviceActionRisk.MEDIUM,
        createdAtEpochMs = 1_100,
        expiresAtEpochMs = 2_000,
        confirmationClass = DeviceConfirmationClass.USER_CONFIRMATION,
        verifierId = verifierId,
        auditCategory = "device-action",
        policyVersion = "policy-v1",
    )

    private fun assertDenied(code: DevicePolicyDenialCode, decision: DeviceActionPolicyDecision) {
        val denied = assertIs<DeviceActionPolicyDecision.Denied>(decision)
        assertEquals(code, denied.code)
    }
}
