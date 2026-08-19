package dev.ed3c.autowebview.device.runtime

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityCatalog
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityDescriptor
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityScope
import dev.ed3c.autowebview.device.catalog.DevicePrivilegeClass
import dev.ed3c.autowebview.device.contract.DeviceActionKind
import dev.ed3c.autowebview.device.contract.DeviceActionPayload
import dev.ed3c.autowebview.device.contract.DeviceActionProposal
import dev.ed3c.autowebview.device.contract.DeviceActionResult
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import dev.ed3c.autowebview.device.contract.DeviceConfirmationReceipt
import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.effects.EffectLedger
import dev.ed3c.autowebview.device.effects.EffectLedgerState
import dev.ed3c.autowebview.device.effects.InMemoryEffectLedgerStore
import dev.ed3c.autowebview.device.policy.DeviceActionPolicy
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DistributionProfile
import dev.ed3c.autowebview.device.verifier.PostconditionEvidence
import dev.ed3c.autowebview.device.verifier.PostconditionObservation
import dev.ed3c.autowebview.device.verifier.PreconditionEvidence
import dev.ed3c.autowebview.device.verifier.VerificationEvidenceSource
import dev.ed3c.autowebview.device.verifier.VerificationPlan
import dev.ed3c.autowebview.device.verifier.VerificationPlanKey
import dev.ed3c.autowebview.device.verifier.VerificationPrivacyClass
import dev.ed3c.autowebview.device.verifier.VerificationRegistry
import dev.ed3c.autowebview.device.workflow.WorkflowDefinition
import dev.ed3c.autowebview.device.workflow.WorkflowEdge
import dev.ed3c.autowebview.device.workflow.WorkflowEdgeKind
import dev.ed3c.autowebview.device.workflow.WorkflowNode
import dev.ed3c.autowebview.device.workflow.WorkflowOrigin
import dev.ed3c.autowebview.device.workflow.WorkflowRevisionAuthorityBinding
import dev.ed3c.autowebview.dispatcher.DispatcherEvent
import dev.ed3c.autowebview.dispatcher.LocalDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceAutomationRuntimeInstrumentedTest {
    @Test
    fun android_runtime_fixture_is_present_without_claiming_live_accessibility_service() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.ed3c.autowebview", context.packageName)
        assertTrue(Build.VERSION.SDK_INT >= 24)
    }

    @Test
    fun verifier_truth_is_the_only_android_fixture_path_to_applied() {
        val fixture = Fixture(postcondition = PostconditionObservation.TRUE)
        val result = fixture.runtime().execute(fixture.request())

        assertTrue(result.actionResult is DeviceActionResult.VerifiedApplied)
        assertEquals(DeviceEffectState.APPLIED, result.effectState)
        assertEquals(EffectLedgerState.TERMINAL_APPLIED, result.effectRecord?.state)
        assertEquals(DeviceEffectState.APPLIED, result.workflowReceipt?.effectState)
        assertEquals(1, fixture.dispatchCount)
    }

    @Test
    fun accepted_platform_callback_with_inconclusive_verifier_stays_unknown() {
        val fixture = Fixture(postcondition = PostconditionObservation.INCONCLUSIVE)
        val result = fixture.runtime().execute(fixture.request())

        assertTrue(result.actionResult is DeviceActionResult.FailedUnknownEffect)
        assertEquals(DeviceEffectState.UNKNOWN, result.effectState)
        assertEquals(EffectLedgerState.TERMINAL_UNKNOWN, result.effectRecord?.state)
        assertTrue(result.effectRecord?.reconciliationRequired == true)
        assertEquals(DeviceEffectState.UNKNOWN, result.workflowReceipt?.effectState)
        assertTrue(fixture.lastCallbackAccepted)
    }

    @Test
    fun existing_local_dispatcher_user_activity_preempts_before_observation() = runBlocking {
        val fixture = Fixture()
        fixture.localDispatcher.dispatch(DispatcherEvent.UserInteractionStarted)

        val result = fixture.runtime().execute(fixture.request())

        assertEquals(DeviceRuntimeTerminalCode.USER_PREEMPTED, result.terminalCode)
        assertEquals(DeviceEffectState.NONE, result.effectState)
        assertEquals(0, fixture.preconditionCount)
        assertEquals(0, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
    }

    @Test
    fun existing_local_dispatcher_user_activity_after_target_resolution_preempts_before_dispatch() {
        val fixture = Fixture(preemptDuringTargetResolution = true)
        val result = fixture.runtime().execute(fixture.request())

        assertEquals(DeviceRuntimeTerminalCode.USER_PREEMPTED, result.terminalCode)
        assertEquals(DeviceEffectState.NONE, result.effectState)
        assertEquals(1, fixture.preconditionCount)
        assertEquals(1, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
    }

    @Test
    fun not_dispatched_preserves_none_and_does_not_advance_workflow() {
        val fixture = Fixture(dispatchResult = DevicePlatformDispatchResult.NotDispatched("user-preempted"))
        val result = fixture.runtime().execute(fixture.request())

        assertEquals(DeviceRuntimeTerminalCode.DISPATCH_NOT_ADMITTED, result.terminalCode)
        assertEquals(DeviceEffectState.NONE, result.effectState)
        assertEquals(EffectLedgerState.TERMINAL_NONE, result.effectRecord?.state)
        assertNull(result.workflowReceipt)
        assertEquals(1, fixture.dispatchCount)
    }

    @Test
    fun uncertain_platform_failure_is_never_rewritten_to_none() {
        val fixture = Fixture(dispatchResult = DevicePlatformDispatchResult.FailureUnknown("platform-unknown"))
        val result = fixture.runtime().execute(fixture.request())

        assertEquals(DeviceRuntimeTerminalCode.PLATFORM_FAILURE_UNKNOWN, result.terminalCode)
        assertEquals(DeviceEffectState.UNKNOWN, result.effectState)
        assertEquals(EffectLedgerState.TERMINAL_UNKNOWN, result.effectRecord?.state)
        assertTrue(result.actionResult is DeviceActionResult.FailedUnknownEffect)
        assertNull(result.workflowReceipt)
    }

    @Test
    fun profile_and_workflow_binding_drift_fail_closed_before_dispatch() {
        val profileFixture = Fixture()
        val widened = profileFixture.proposal().copy(profile = DistributionProfile.ACCESSIBILITY_TOOL)
        val profileResult = profileFixture.runtime().execute(profileFixture.request().copy(proposal = widened))
        assertEquals(DeviceRuntimeTerminalCode.PROFILE_MISMATCH, profileResult.terminalCode)
        assertEquals(0, profileFixture.preconditionCount)
        assertEquals(0, profileFixture.targetResolutionCount)
        assertEquals(0, profileFixture.dispatchCount)

        val bindingFixture = Fixture()
        val request = bindingFixture.request()
        val staleBinding = request.authorityBinding.copy(revision = 2)
        val bindingResult = bindingFixture.runtime().execute(request.copy(authorityBinding = staleBinding))
        assertEquals(DeviceRuntimeTerminalCode.WORKFLOW_BINDING_INVALID, bindingResult.terminalCode)
        assertEquals(0, bindingFixture.preconditionCount)
        assertEquals(0, bindingFixture.targetResolutionCount)
        assertEquals(0, bindingFixture.dispatchCount)
    }

    private class Fixture(
        private val postcondition: PostconditionObservation = PostconditionObservation.TRUE,
        private val dispatchResult: DevicePlatformDispatchResult? = null,
        private val preemptDuringTargetResolution: Boolean = false,
    ) {
        private val capabilityId = DeviceCapabilityId("accessibility-click")
        private val digestA = "a".repeat(64)
        private val digestB = "b".repeat(64)
        private val digestC = "c".repeat(64)
        private val digestD = "d".repeat(64)
        private val digestF = "f".repeat(64)
        private val subject = DeviceSubjectRef(
            packageName = "dev.ed3c.fixture",
            windowId = "window-1",
            displayId = "display-0",
            snapshotVersion = 1,
            capturedAtEpochMs = 1_000,
        )
        private val target = DeviceTargetRef.UiTarget("fingerprint-1", 1)
        private val descriptor = DeviceCapabilityDescriptor(
            id = capabilityId,
            canonicalActionIds = setOf("accessibility.click"),
            actionKinds = setOf(DeviceActionKind.UI_CLICK),
            allowedProfiles = setOf(DistributionProfile.ENTERPRISE_SIDELOAD),
            scope = DeviceCapabilityScope.DEVICE_WIDE_ACCESSIBILITY,
            privilegeClass = DevicePrivilegeClass.ACCESSIBILITY,
            maximumRisk = DeviceActionRisk.HIGH,
            confirmationClass = DeviceConfirmationClass.USER_CONFIRMATION,
            verifierId = "verify-click",
            auditCategory = "device-ui-action",
        )
        private val catalog = DeviceCapabilityCatalog(listOf(descriptor))
        private val verifier = VerificationRegistry(
            listOf(
                VerificationPlan(
                    key = VerificationPlanKey(capabilityId, "accessibility.click", "verify-click", 1),
                    maximumObservationAgeMs = 10_000,
                ),
            ),
        )
        private val effectLedger = EffectLedger(InMemoryEffectLedgerStore())
        private val clock = DeviceRuntimeClock { 1_400 }
        val localDispatcher = LocalDispatcher()

        var preconditionCount = 0
        var targetResolutionCount = 0
        var dispatchCount = 0
        var lastCallbackAccepted = false

        fun proposal() = DeviceActionProposal(
            proposalId = "proposal-android-1",
            intentId = "intent-android-1",
            canonicalActionId = "accessibility.click",
            capabilityId = capabilityId,
            profile = DistributionProfile.ENTERPRISE_SIDELOAD,
            subject = subject,
            target = target,
            kind = DeviceActionKind.UI_CLICK,
            payload = DeviceActionPayload.UiClick,
            payloadDigestSha256 = digestA,
            risk = DeviceActionRisk.MEDIUM,
            createdAtEpochMs = 1_100,
            expiresAtEpochMs = 5_000,
            confirmationClass = DeviceConfirmationClass.USER_CONFIRMATION,
            verifierId = "verify-click",
            auditCategory = "device-ui-action",
            policyVersion = "policy-v1",
        )

        private fun confirmation() = DeviceConfirmationReceipt(
            receiptId = "confirm-android-1",
            proposalId = "proposal-android-1",
            canonicalActionId = "accessibility.click",
            capabilityId = capabilityId,
            profile = DistributionProfile.ENTERPRISE_SIDELOAD,
            subject = subject,
            target = target,
            payloadDigestSha256 = digestA,
            policyVersion = "policy-v1",
            confirmedAtEpochMs = 1_200,
            expiresAtEpochMs = 4_000,
        )

        private fun workflow() = WorkflowDefinition(
            workflowId = "workflow-android-1",
            revision = 1,
            digestSha256 = digestC,
            origin = WorkflowOrigin.HUMAN_AUTHORED,
            profile = DistributionProfile.ENTERPRISE_SIDELOAD,
            nodes = listOf(
                WorkflowNode.ActionTemplate(
                    nodeId = "action-1",
                    capabilityId = capabilityId,
                    canonicalActionId = "accessibility.click",
                    kind = DeviceActionKind.UI_CLICK,
                    profile = DistributionProfile.ENTERPRISE_SIDELOAD,
                    risk = DeviceActionRisk.MEDIUM,
                    verifierId = "verify-click",
                    requiresConfirmation = true,
                    resourceLeases = setOf("ui-device"),
                ),
                WorkflowNode.VerificationGate("verify-1", "action-1", "verify-click"),
            ),
            edges = listOf(WorkflowEdge("action-1", "verify-1", WorkflowEdgeKind.COMPLETION)),
        )

        fun request() = DeviceRuntimeExecutionRequest(
            ingress = DeviceRuntimeIngress.MODEL_PROPOSAL,
            proposal = proposal(),
            workflow = workflow(),
            actionNodeId = "action-1",
            authorityBinding = WorkflowRevisionAuthorityBinding(
                workflowId = "workflow-android-1",
                revision = 1,
                workflowDigestSha256 = digestC,
                nodeId = "action-1",
                confirmationReceiptId = "confirm-android-1",
                idempotencySubject = "idem-android-1",
            ),
            confirmationReceipt = confirmation(),
            idempotencyKey = "idem-android-1",
        )

        fun runtime(): DeviceAutomationRuntime {
            val policy = DeviceActionPolicy(
                compiledProfile = DistributionProfile.ENTERPRISE_SIDELOAD,
                descriptors = listOf(descriptor),
                enabledCapabilityIds = setOf(capabilityId),
            )
            val targetResolver = DeviceTargetResolver { proposal, _ ->
                targetResolutionCount += 1
                if (preemptDuringTargetResolution) {
                    runBlocking { localDispatcher.dispatch(DispatcherEvent.UserInteractionStarted) }
                }
                DeviceTargetResolution.Resolved(
                    DeviceResolvedTarget(
                        subject = proposal.subject,
                        target = proposal.target,
                        resolvedTargetToken = "android-target-token-1",
                        tokenDigestSha256 = digestB,
                        issuedAtEpochMs = 1_300,
                        expiresAtEpochMs = 3_000,
                    ),
                )
            }
            val preconditions = DevicePreconditionProvider { identity, _ ->
                preconditionCount += 1
                PreconditionEvidence(
                    identity = identity,
                    observedAtEpochMs = 1_250,
                    evidenceDigestSha256 = digestD,
                    source = VerificationEvidenceSource.SANITIZED_UI,
                    privacyClass = VerificationPrivacyClass.SANITIZED_DIGEST,
                )
            }
            val postconditions = DevicePostconditionProvider { identity, _, dispatch, _ ->
                lastCallbackAccepted = dispatch.platformCallbackAccepted
                PostconditionEvidence(
                    identity = identity,
                    observedAtEpochMs = 1_400,
                    evidenceDigestSha256 = digestF,
                    source = VerificationEvidenceSource.SANITIZED_UI,
                    privacyClass = VerificationPrivacyClass.SANITIZED_DIGEST,
                    observation = postcondition,
                )
            }
            val platform = DevicePlatformDispatcher { _, _ ->
                dispatchCount += 1
                dispatchResult ?: DevicePlatformDispatchResult.Dispatched(
                    DevicePlatformDispatchEvidence("dispatch-android-1", platformCallbackAccepted = true),
                )
            }
            val baseAuthority = DeviceRuntimeAuthoritySnapshot(
                authorityEpoch = "authority-android-1",
                compiledProfile = DistributionProfile.ENTERPRISE_SIDELOAD,
                policyVersion = "policy-v1",
                workflowId = "workflow-android-1",
                workflowRevision = 1,
                workflowDigestSha256 = digestC,
                currentSubject = subject,
                enabledCapabilityIds = setOf(capabilityId),
                userInteractionActive = false,
                screenLocked = false,
                platformAvailable = true,
            )
            val authority = LocalDispatcherDeviceRuntimeAuthoritySource(
                dispatcher = localDispatcher,
                delegate = DeviceRuntimeAuthoritySource { baseAuthority },
            )
            return DeviceAutomationRuntime(
                compiledProfile = DistributionProfile.ENTERPRISE_SIDELOAD,
                capabilityCatalog = catalog,
                policy = policy,
                verificationRegistry = verifier,
                effectLedger = effectLedger,
                targetResolver = targetResolver,
                preconditionProvider = preconditions,
                postconditionProvider = postconditions,
                platformDispatcher = platform,
                authoritySource = authority,
                auditSink = DeviceRuntimeAuditSink { true },
                clock = clock,
            )
        }
    }
}
