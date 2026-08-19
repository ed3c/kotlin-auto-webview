package dev.ed3c.autowebview.device.runtime

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceAutomationRuntimeTest {
    @Test
    fun verified_postcondition_is_the_only_path_to_applied() {
        val fixture = Fixture(postcondition = PostconditionObservation.TRUE)
        val result = fixture.runtime().execute(fixture.request())

        assertIs<DeviceActionResult.VerifiedApplied>(result.actionResult)
        assertEquals(DeviceEffectState.APPLIED, result.effectState)
        assertEquals(EffectLedgerState.TERMINAL_APPLIED, result.effectRecord?.state)
        assertEquals(DeviceEffectState.APPLIED, result.workflowReceipt?.effectState)
        assertEquals(1, fixture.dispatchCount)
        assertEquals(1, fixture.targetResolutionCount)
        assertTrue(result.auditCommitted)
        assertTrue(result.trace.any { it.state == DeviceRuntimeState.VERIFIED_APPLIED })
        assertTrue(result.trace.indexOfFirst { it.state == DeviceRuntimeState.CAPABILITY_POLICY_EVALUATED } <
            result.trace.indexOfFirst { it.state == DeviceRuntimeState.TARGET_RESOLUTION_REQUESTED })
        assertTrue(result.trace.indexOfFirst { it.state == DeviceRuntimeState.FINAL_AUTHORITY_REVALIDATION } <
            result.trace.indexOfFirst { it.state == DeviceRuntimeState.DISPATCHING_TYPED_COMMAND })
    }

    @Test
    fun platform_callback_success_never_becomes_completion_without_verifier_truth() {
        val fixture = Fixture(postcondition = PostconditionObservation.INCONCLUSIVE)
        val result = fixture.runtime().execute(fixture.request())

        assertIs<DeviceActionResult.FailedUnknownEffect>(result.actionResult)
        assertEquals(DeviceEffectState.UNKNOWN, result.effectState)
        assertEquals(EffectLedgerState.TERMINAL_UNKNOWN, result.effectRecord?.state)
        assertTrue(result.effectRecord?.reconciliationRequired == true)
        assertEquals(DeviceEffectState.UNKNOWN, result.workflowReceipt?.effectState)
        assertFalse(result.trace.any { it.state == DeviceRuntimeState.VERIFIED_APPLIED })
        assertTrue(result.trace.any { it.state == DeviceRuntimeState.RECONCILIATION_REQUIRED })
        assertEquals(true, fixture.lastDispatchEvidence?.platformCallbackAccepted)
    }

    @Test
    fun policy_denial_happens_before_precondition_target_resolution_or_dispatch() {
        val fixture = Fixture(enabledCapabilities = emptySet())
        val result = fixture.runtime().execute(fixture.request())

        assertIs<DeviceActionResult.Rejected>(result.actionResult)
        assertEquals(DeviceRuntimeTerminalCode.POLICY_DENIED, result.terminalCode)
        assertEquals(0, fixture.preconditionCount)
        assertEquals(0, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
    }

    @Test
    fun canonical_audit_category_drift_is_rejected_before_policy_targeting_or_dispatch() {
        val fixture = Fixture()
        val drifted = fixture.proposal().copy(auditCategory = "different-audit-category")
        val result = fixture.runtime().execute(fixture.request().copy(proposal = drifted))

        assertEquals(DeviceRuntimeTerminalCode.CANONICAL_ACTION_MISMATCH, result.terminalCode)
        assertEquals(0, fixture.preconditionCount)
        assertEquals(0, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
    }

    @Test
    fun all_remote_and_model_ingress_remain_proposal_only_and_cannot_bypass_policy() {
        for (ingress in listOf(DeviceRuntimeIngress.MCP_PROPOSAL, DeviceRuntimeIngress.REMOTE_PROPOSAL, DeviceRuntimeIngress.MODEL_PROPOSAL)) {
            val fixture = Fixture(enabledCapabilities = emptySet())
            val result = fixture.runtime().execute(fixture.request().copy(ingress = ingress))
            assertEquals(DeviceRuntimeTerminalCode.POLICY_DENIED, result.terminalCode)
            assertEquals(0, fixture.targetResolutionCount)
            assertEquals(0, fixture.dispatchCount)
        }
    }

    @Test
    fun runtime_profile_widening_is_rejected_before_capability_policy_or_targeting() {
        val fixture = Fixture()
        val widened = fixture.proposal().copy(profile = DistributionProfile.ACCESSIBILITY_TOOL)
        val result = fixture.runtime().execute(fixture.request().copy(proposal = widened))

        assertEquals(DeviceRuntimeTerminalCode.PROFILE_MISMATCH, result.terminalCode)
        assertEquals(0, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
    }

    @Test
    fun missing_confirmation_stops_before_precondition_and_target_resolution() {
        val fixture = Fixture()
        val result = fixture.runtime().execute(fixture.request().copy(confirmationReceipt = null))

        assertIs<DeviceActionResult.UserActionRequired>(result.actionResult)
        assertEquals(DeviceRuntimeTerminalCode.CONFIRMATION_REQUIRED, result.terminalCode)
        assertEquals(0, fixture.preconditionCount)
        assertEquals(0, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
    }

    @Test
    fun expired_confirmation_stops_before_observation() {
        val fixture = Fixture()
        val request = fixture.request()
        val expired = assertNotNull(request.confirmationReceipt).copy(expiresAtEpochMs = 1_300)
        val result = fixture.runtime().execute(request.copy(confirmationReceipt = expired))

        assertEquals(DeviceRuntimeTerminalCode.CONFIRMATION_INVALID, result.terminalCode)
        assertEquals(0, fixture.preconditionCount)
        assertEquals(0, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
    }

    @Test
    fun workflow_risk_drift_is_rejected_before_precondition_targeting_or_dispatch() {
        val fixture = Fixture()
        val original = fixture.workflow()
        val drifted = original.copy(
            nodes = original.nodes.map { node ->
                if (node is WorkflowNode.ActionTemplate && node.nodeId == "action-1") {
                    node.copy(risk = DeviceActionRisk.LOW)
                } else {
                    node
                }
            },
        )
        val result = fixture.runtime().execute(fixture.request().copy(workflow = drifted))

        assertEquals(DeviceRuntimeTerminalCode.WORKFLOW_INVALID, result.terminalCode)
        assertEquals(0, fixture.preconditionCount)
        assertEquals(0, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
    }

    @Test
    fun user_interaction_preempts_before_observation_or_target_resolution() {
        val fixture = Fixture(userInteractionActive = true)
        val result = fixture.runtime().execute(fixture.request())

        assertIs<DeviceActionResult.CancelledBeforeEffect>(result.actionResult)
        assertEquals(DeviceRuntimeTerminalCode.USER_PREEMPTED, result.terminalCode)
        assertEquals(DeviceEffectState.NONE, result.effectState)
        assertEquals(0, fixture.preconditionCount)
        assertEquals(0, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
        assertTrue(result.trace.any { it.detailCode == "pre-observation-user-preempted" })
    }

    @Test
    fun user_interaction_after_resolution_preempts_at_final_gate_before_dispatch() {
        val fixture = Fixture(
            finalAuthorityMutation = { it.copy(userInteractionActive = true) },
        )
        val result = fixture.runtime().execute(fixture.request())

        assertIs<DeviceActionResult.CancelledBeforeEffect>(result.actionResult)
        assertEquals(DeviceRuntimeTerminalCode.USER_PREEMPTED, result.terminalCode)
        assertEquals(DeviceEffectState.NONE, result.effectState)
        assertEquals(1, fixture.preconditionCount)
        assertEquals(1, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
        assertTrue(result.trace.any { it.state == DeviceRuntimeState.FINAL_AUTHORITY_REVALIDATION })
    }

    @Test
    fun stale_authority_context_fails_closed_before_observation() {
        val mutations = listOf<Pair<(DeviceRuntimeAuthoritySnapshot) -> DeviceRuntimeAuthoritySnapshot, DeviceRuntimeTerminalCode>>(
            ({ it.copy(currentSubject = it.currentSubject.copy(packageName = "dev.ed3c.other")) }) to DeviceRuntimeTerminalCode.SUBJECT_CHANGED,
            ({ it.copy(currentSubject = it.currentSubject.copy(windowId = "window-2")) }) to DeviceRuntimeTerminalCode.SUBJECT_CHANGED,
            ({ it.copy(currentSubject = it.currentSubject.copy(snapshotVersion = 2)) }) to DeviceRuntimeTerminalCode.SUBJECT_CHANGED,
            ({ it.copy(screenLocked = true) }) to DeviceRuntimeTerminalCode.SCREEN_LOCKED,
            ({ it.copy(platformAvailable = false) }) to DeviceRuntimeTerminalCode.PLATFORM_UNAVAILABLE,
            ({ it.copy(compiledProfile = DistributionProfile.PLAY_SAFE) }) to DeviceRuntimeTerminalCode.PROFILE_MISMATCH,
            ({ it.copy(policyVersion = "policy-v2") }) to DeviceRuntimeTerminalCode.POLICY_CHANGED,
            ({ it.copy(workflowRevision = 2) }) to DeviceRuntimeTerminalCode.WORKFLOW_CHANGED,
            ({ it.copy(enabledCapabilityIds = emptySet()) }) to DeviceRuntimeTerminalCode.CAPABILITY_REVOKED,
        )
        mutations.forEach { (mutation, code) ->
            val fixture = Fixture(authorityMutation = mutation)
            val result = fixture.runtime().execute(fixture.request())
            assertEquals(code, result.terminalCode)
            assertEquals(0, fixture.preconditionCount)
            assertEquals(0, fixture.targetResolutionCount)
            assertEquals(0, fixture.dispatchCount)
            assertNull(result.effectRecord)
        }
    }

    @Test
    fun target_token_expiry_after_resolution_stops_before_dispatch() {
        val fixture = Fixture(targetExpiresAtEpochMs = 1_300)
        val result = fixture.runtime().execute(fixture.request())

        assertEquals(DeviceRuntimeTerminalCode.TARGET_TOKEN_EXPIRED, result.terminalCode)
        assertEquals(1, fixture.preconditionCount)
        assertEquals(1, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
    }

    @Test
    fun prebound_target_digest_disagreement_stops_before_dispatch() {
        val fixture = Fixture()
        val request = fixture.request()
        val mismatched = request.authorityBinding.copy(targetTokenDigestSha256 = "c".repeat(64))
        val result = fixture.runtime().execute(request.copy(authorityBinding = mismatched))

        assertEquals(DeviceRuntimeTerminalCode.TARGET_BINDING_MISMATCH, result.terminalCode)
        assertEquals(1, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
    }

    @Test
    fun stale_workflow_authority_binding_never_reaches_precondition_or_targeting() {
        val fixture = Fixture()
        val stale = fixture.request().authorityBinding.copy(revision = 2)
        val result = fixture.runtime().execute(fixture.request().copy(authorityBinding = stale))

        assertEquals(DeviceRuntimeTerminalCode.WORKFLOW_BINDING_INVALID, result.terminalCode)
        assertEquals(0, fixture.preconditionCount)
        assertEquals(0, fixture.targetResolutionCount)
        assertEquals(0, fixture.dispatchCount)
    }

    @Test
    fun platform_not_dispatched_after_admission_preserves_none_and_never_advances_workflow() {
        val fixture = Fixture(
            dispatchResult = DevicePlatformDispatchResult.NotDispatched("user-preempted"),
        )
        val result = fixture.runtime().execute(fixture.request())

        assertIs<DeviceActionResult.CancelledBeforeEffect>(result.actionResult)
        assertEquals(DeviceRuntimeTerminalCode.DISPATCH_NOT_ADMITTED, result.terminalCode)
        assertEquals(DeviceEffectState.NONE, result.effectState)
        assertEquals(1, fixture.dispatchCount)
        assertEquals(EffectLedgerState.TERMINAL_NONE, result.effectRecord?.state)
        assertNull(result.workflowReceipt)
        assertFalse(result.trace.any { it.state == DeviceRuntimeState.VERIFIED_APPLIED })
    }

    @Test
    fun unknown_effect_receipt_blocks_dependent_workflow_admission_and_retry() {
        val fixture = Fixture(postcondition = PostconditionObservation.OBSERVER_LOST)
        val result = fixture.runtime().execute(fixture.request())
        val receipt = assertNotNull(result.workflowReceipt)

        assertEquals(DeviceEffectState.UNKNOWN, receipt.effectState)
        val dependent = fixture.workflowWithDependentNode()
        val decision = dev.ed3c.autowebview.device.workflow.WorkflowAdmission().canStart(
            definition = dependent,
            nodeId = "dependent-action",
            receipts = mapOf("action-1" to receipt),
            confirmedNodeIds = setOf("dependent-action"),
            activeResourceLeases = emptySet(),
        )
        assertFalse(decision.ready)
        assertEquals(
            dev.ed3c.autowebview.device.workflow.WorkflowAdmissionCode.UNKNOWN_EFFECT_BLOCKS_COMPLETION,
            decision.code,
        )
        assertFalse(dev.ed3c.autowebview.device.workflow.WorkflowAdmission().canRetry(receipt, idempotent = true))
    }

    @Test
    fun target_ambiguity_is_a_pre_dispatch_terminal_none_state() {
        val fixture = Fixture(targetResolution = DeviceTargetResolution.Ambiguous)
        val result = fixture.runtime().execute(fixture.request())

        assertEquals(DeviceRuntimeTerminalCode.TARGET_AMBIGUOUS, result.terminalCode)
        assertEquals(DeviceEffectState.NONE, result.effectState)
        assertEquals(0, fixture.dispatchCount)
        assertNull(result.effectRecord)
    }

    @Test
    fun stale_convergence_source_subject_is_rejected_by_the_exact_binding_control() {
        val selected = DeviceRuntimeConvergenceSubjects.SELECTED
        assertTrue(selected.matchesSelectedSources())
        val stale = selected.copy(
            workflow = selected.workflow.copy(headCommit = "0".repeat(40)),
        )
        assertFalse(stale.matchesSelectedSources())
    }

    private class Fixture(
        private val postcondition: PostconditionObservation = PostconditionObservation.TRUE,
        private val targetResolution: DeviceTargetResolution? = null,
        private val userInteractionActive: Boolean = false,
        private val enabledCapabilities: Set<DeviceCapabilityId>? = null,
        private val authorityMutation: ((DeviceRuntimeAuthoritySnapshot) -> DeviceRuntimeAuthoritySnapshot)? = null,
        private val finalAuthorityMutation: ((DeviceRuntimeAuthoritySnapshot) -> DeviceRuntimeAuthoritySnapshot)? = null,
        private val targetExpiresAtEpochMs: Long = 3_000,
        private val dispatchResult: DevicePlatformDispatchResult? = null,
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
        private val verificationRegistry = VerificationRegistry(
            listOf(
                VerificationPlan(
                    key = VerificationPlanKey(capabilityId, "accessibility.click", "verify-click", 1),
                    maximumObservationAgeMs = 10_000,
                ),
            ),
        )
        private val effectLedger = EffectLedger(InMemoryEffectLedgerStore())
        private val clock = DeviceRuntimeClock { 1_400 }

        var preconditionCount = 0
        var targetResolutionCount = 0
        var dispatchCount = 0
        var lastDispatchEvidence: DevicePlatformDispatchEvidence? = null

        fun proposal() = DeviceActionProposal(
            proposalId = "proposal-1",
            intentId = "intent-1",
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
            receiptId = "confirm-1",
            proposalId = "proposal-1",
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

        fun workflow() = WorkflowDefinition(
            workflowId = "workflow-1",
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

        fun workflowWithDependentNode(): WorkflowDefinition {
            val base = workflow()
            return base.copy(
                nodes = base.nodes + listOf(
                    WorkflowNode.ActionTemplate(
                        nodeId = "dependent-action",
                        capabilityId = capabilityId,
                        canonicalActionId = "accessibility.click",
                        kind = DeviceActionKind.UI_CLICK,
                        profile = DistributionProfile.ENTERPRISE_SIDELOAD,
                        risk = DeviceActionRisk.MEDIUM,
                        verifierId = "verify-click",
                        requiresConfirmation = true,
                        resourceLeases = setOf("ui-device-2"),
                    ),
                    WorkflowNode.VerificationGate("verify-2", "dependent-action", "verify-click"),
                ),
                edges = base.edges + listOf(
                    WorkflowEdge("action-1", "dependent-action", WorkflowEdgeKind.COMPLETION),
                    WorkflowEdge("dependent-action", "verify-2", WorkflowEdgeKind.COMPLETION),
                ),
            )
        }

        fun request() = DeviceRuntimeExecutionRequest(
            ingress = DeviceRuntimeIngress.MODEL_PROPOSAL,
            proposal = proposal(),
            workflow = workflow(),
            actionNodeId = "action-1",
            authorityBinding = WorkflowRevisionAuthorityBinding(
                workflowId = "workflow-1",
                revision = 1,
                workflowDigestSha256 = digestC,
                nodeId = "action-1",
                confirmationReceiptId = "confirm-1",
                idempotencySubject = "idem-1",
            ),
            confirmationReceipt = confirmation(),
            idempotencyKey = "idem-1",
        )

        fun runtime(): DeviceAutomationRuntime {
            val enabled = enabledCapabilities ?: setOf(capabilityId)
            val policy = DeviceActionPolicy(
                compiledProfile = DistributionProfile.ENTERPRISE_SIDELOAD,
                descriptors = listOf(descriptor),
                enabledCapabilityIds = enabled,
            )
            val targetResolver = DeviceTargetResolver { proposal, _ ->
                targetResolutionCount += 1
                targetResolution ?: DeviceTargetResolution.Resolved(
                    DeviceResolvedTarget(
                        subject = proposal.subject,
                        target = proposal.target,
                        resolvedTargetToken = "target-token-1",
                        tokenDigestSha256 = digestB,
                        issuedAtEpochMs = 1_300,
                        expiresAtEpochMs = targetExpiresAtEpochMs,
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
                lastDispatchEvidence = dispatch
                PostconditionEvidence(
                    identity = identity,
                    observedAtEpochMs = 1_400,
                    evidenceDigestSha256 = digestF,
                    source = VerificationEvidenceSource.SANITIZED_UI,
                    privacyClass = VerificationPrivacyClass.SANITIZED_DIGEST,
                    observation = postcondition,
                )
            }
            val dispatcher = DevicePlatformDispatcher { _, _ ->
                dispatchCount += 1
                val configured = dispatchResult
                if (configured != null) {
                    configured
                } else {
                    val evidence = DevicePlatformDispatchEvidence("dispatch-1", platformCallbackAccepted = true)
                    lastDispatchEvidence = evidence
                    DevicePlatformDispatchResult.Dispatched(evidence)
                }
            }
            val baseAuthority = DeviceRuntimeAuthoritySnapshot(
                authorityEpoch = "authority-1",
                compiledProfile = DistributionProfile.ENTERPRISE_SIDELOAD,
                policyVersion = "policy-v1",
                workflowId = "workflow-1",
                workflowRevision = 1,
                workflowDigestSha256 = digestC,
                currentSubject = subject,
                enabledCapabilityIds = enabled,
                userInteractionActive = userInteractionActive,
                screenLocked = false,
                platformAvailable = true,
            )
            var authorityReadCount = 0
            val authority = DeviceRuntimeAuthoritySource {
                authorityReadCount += 1
                when {
                    authorityReadCount >= 2 && finalAuthorityMutation != null -> finalAuthorityMutation.invoke(baseAuthority)
                    authorityMutation != null -> authorityMutation.invoke(baseAuthority)
                    else -> baseAuthority
                }
            }
            return DeviceAutomationRuntime(
                compiledProfile = DistributionProfile.ENTERPRISE_SIDELOAD,
                capabilityCatalog = catalog,
                policy = policy,
                verificationRegistry = verificationRegistry,
                effectLedger = effectLedger,
                targetResolver = targetResolver,
                preconditionProvider = preconditions,
                postconditionProvider = postconditions,
                platformDispatcher = dispatcher,
                authoritySource = authority,
                auditSink = DeviceRuntimeAuditSink { true },
                clock = clock,
            )
        }
    }
}
