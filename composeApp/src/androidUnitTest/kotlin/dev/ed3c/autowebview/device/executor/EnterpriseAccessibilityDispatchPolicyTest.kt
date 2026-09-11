package dev.ed3c.autowebview.device.executor

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityScope
import dev.ed3c.autowebview.device.catalog.DevicePrivilegeClass
import dev.ed3c.autowebview.device.contract.DeviceActionCommand
import dev.ed3c.autowebview.device.contract.DeviceActionKind
import dev.ed3c.autowebview.device.contract.DeviceActionPayload
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DistributionProfile
import dev.ed3c.autowebview.device.verifier.VerificationCoverageCode
import dev.ed3c.autowebview.device.verifier.VerificationCoverageOracle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnterpriseAccessibilityDispatchPolicyTest {
    private val policy = EnterpriseAccessibilityDispatchPolicy()

    @Test
    fun descriptor_is_enterprise_accessibility_only_and_requires_human_confirmation() {
        val descriptor = EnterpriseAccessibilityClickContract.descriptor()

        assertEquals(setOf(DistributionProfile.ENTERPRISE_SIDELOAD), descriptor.allowedProfiles)
        assertEquals(DeviceCapabilityScope.DEVICE_WIDE_ACCESSIBILITY, descriptor.scope)
        assertEquals(DevicePrivilegeClass.ACCESSIBILITY, descriptor.privilegeClass)
        assertEquals(DeviceConfirmationClass.USER_CONFIRMATION, descriptor.confirmationClass)
        assertFalse(DistributionProfile.PLAY_SAFE in descriptor.allowedProfiles)
    }

    @Test
    fun exact_click_command_is_admitted() {
        assertEquals(
            EnterpriseAccessibilityDispatchDecision.ADMITTED,
            policy.evaluate(command()),
        )
    }

    @Test
    fun wrong_profile_and_capability_fail_closed() {
        assertEquals(
            EnterpriseAccessibilityDispatchDecision.WRONG_PROFILE,
            policy.evaluate(command(profile = DistributionProfile.PLAY_SAFE)),
        )
        assertEquals(
            EnterpriseAccessibilityDispatchDecision.WRONG_CAPABILITY,
            policy.evaluate(command(capabilityId = DeviceCapabilityId("other-capability"))),
        )
    }

    @Test
    fun wrong_action_kind_or_target_cannot_smuggle_another_execution_family() {
        assertEquals(
            EnterpriseAccessibilityDispatchDecision.UNSUPPORTED_ACTION_KIND,
            policy.evaluate(
                command(
                    kind = DeviceActionKind.UI_FILL_TEXT,
                    payload = DeviceActionPayload.UiFillText("bounded"),
                ),
            ),
        )
        assertEquals(
            EnterpriseAccessibilityDispatchDecision.WRONG_TARGET_KIND,
            policy.evaluate(
                command(
                    target = DeviceTargetRef.ResourceTarget(
                        resourceType = "package",
                        resourceId = "managed-app",
                    ),
                ),
            ),
        )
    }

    @Test
    fun confirmation_is_mandatory_at_the_platform_boundary_too() {
        assertEquals(
            EnterpriseAccessibilityDispatchDecision.CONFIRMATION_MISSING,
            policy.evaluate(command(confirmationReceiptId = null)),
        )
    }

    @Test
    fun state_changing_descriptor_has_exactly_one_current_matching_verifier_plan() {
        val result = VerificationCoverageOracle().evaluate(
            descriptors = listOf(EnterpriseAccessibilityClickContract.descriptor()),
            plans = listOf(EnterpriseAccessibilityClickContract.verificationPlan()),
        )

        assertEquals(VerificationCoverageCode.COMPLETE, result.code)
        assertTrue(result.complete)
    }

    private fun command(
        profile: DistributionProfile = DistributionProfile.ENTERPRISE_SIDELOAD,
        capabilityId: DeviceCapabilityId = EnterpriseAccessibilityClickContract.capabilityId,
        canonicalActionId: String = EnterpriseAccessibilityClickContract.CANONICAL_ACTION_ID,
        kind: DeviceActionKind = DeviceActionKind.UI_CLICK,
        payload: DeviceActionPayload = DeviceActionPayload.UiClick,
        target: DeviceTargetRef = DeviceTargetRef.UiTarget(
            fingerprint = "fingerprint-01",
            snapshotVersion = 7,
        ),
        confirmationReceiptId: String? = "confirmation-01",
    ): DeviceActionCommand = DeviceActionCommand(
        proposalId = "proposal-01",
        canonicalActionId = canonicalActionId,
        capabilityId = capabilityId,
        profile = profile,
        subject = DeviceSubjectRef(
            packageName = "com.example.managed",
            windowId = "window-10",
            displayId = "display-0",
            snapshotVersion = 7,
            capturedAtEpochMs = 100,
        ),
        target = target,
        resolvedTargetToken = "opaque-target-token-01",
        kind = kind,
        payload = payload,
        payloadDigestSha256 = "a".repeat(64),
        verifierId = EnterpriseAccessibilityClickContract.VERIFIER_ID,
        policyVersion = "enterprise-policy-v1",
        confirmationReceiptId = confirmationReceiptId,
    )
}
