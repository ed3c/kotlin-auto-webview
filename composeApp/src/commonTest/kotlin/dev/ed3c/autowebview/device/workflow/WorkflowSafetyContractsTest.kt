package dev.ed3c.autowebview.device.workflow

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityCatalog
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityDescriptor
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityScope
import dev.ed3c.autowebview.device.catalog.DevicePrivilegeClass
import dev.ed3c.autowebview.device.contract.DeviceActionKind
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DistributionProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowSafetyContractsTest {
    @Test
    fun declassification_is_named_versioned_digest_only_and_profile_bound() {
        val workflow = parallelWorkflow()
        val source = WorkflowFieldRef(
            nodeId = "human-source",
            fieldId = "display-name",
            valueType = WorkflowValueType.TEXT,
            taint = WorkflowTaintClass.USER_CONTENT,
        )
        val registry = WorkflowDeclassificationRegistry(
            listOf(
                WorkflowDeclassificationRule(
                    ruleId = "user-content-to-sanitized",
                    version = 1,
                    fromTaint = WorkflowTaintClass.USER_CONTENT,
                    toTaint = WorkflowTaintClass.SANITIZED_UI_METADATA,
                    valueType = WorkflowValueType.TEXT,
                    transformId = "normalize-label-v1",
                    allowedProfiles = setOf(DistributionProfile.PLAY_SAFE),
                ),
            ),
        )
        val receipt = registry.declassify(
            workflow,
            source,
            "user-content-to-sanitized",
            1,
            "a".repeat(64),
            "b".repeat(64),
        )
        assertTrue(receipt.matches(workflow))
        val encoded = Json.encodeToString(WorkflowDeclassificationReceipt.serializer(), receipt)
        assertFalse(encoded.contains("rawValue"))
        assertFalse(encoded.contains("hunter2"))
        assertFailsWith<IllegalStateException> {
            registry.declassify(workflow, source, "missing-rule", 1, "a".repeat(64), "b".repeat(64))
        }
    }

    @Test
    fun high_sensitivity_taints_have_no_declassification_path() {
        for (taint in listOf(
            WorkflowTaintClass.PAYMENT,
            WorkflowTaintClass.PASSWORD,
            WorkflowTaintClass.SECRET,
            WorkflowTaintClass.PRIVATE_MESSAGE,
            WorkflowTaintClass.PRIVILEGED_RESULT,
        )) {
            assertFailsWith<IllegalArgumentException> {
                WorkflowDeclassificationRule(
                    ruleId = "unsafe-rule",
                    version = 1,
                    fromTaint = taint,
                    toTaint = WorkflowTaintClass.PUBLIC,
                    valueType = WorkflowValueType.TEXT,
                    transformId = "unsafe-transform",
                    allowedProfiles = setOf(DistributionProfile.PLAY_SAFE),
                )
            }
        }
    }

    @Test
    fun revision_change_invalidates_confirmation_target_and_idempotency_authority_together() {
        val workflow = parallelWorkflow()
        val binding = WorkflowRevisionAuthorityBinding(
            workflowId = workflow.workflowId,
            revision = workflow.revision,
            workflowDigestSha256 = workflow.digestSha256,
            nodeId = "action-a",
            confirmationReceiptId = "confirmation-1",
            targetTokenDigestSha256 = "c".repeat(64),
            idempotencySubject = "idempotency-1",
        )
        assertTrue(binding.isValidFor(workflow))
        assertFalse(binding.isValidFor(workflow.copy(revision = 2, digestSha256 = "d".repeat(64))))
    }

    @Test
    fun planner_admits_resource_disjoint_siblings_without_serializing_them() {
        val workflow = parallelWorkflow()
        val ready = WorkflowReadySetPlanner().readyActionNodeIds(
            definition = workflow,
            candidateNodeIds = setOf("action-b", "action-a"),
            receipts = emptyMap(),
            confirmedNodeIds = setOf("action-a", "action-b"),
            activeResourceLeases = emptySet(),
        )
        assertEquals(listOf("action-a", "action-b"), ready)
    }

    @Test
    fun planner_blocks_overlapping_resource_leases() {
        val workflow = parallelWorkflow(sharedLease = true)
        val ready = WorkflowReadySetPlanner().readyActionNodeIds(
            definition = workflow,
            candidateNodeIds = setOf("action-a", "action-b"),
            receipts = emptyMap(),
            confirmedNodeIds = setOf("action-a", "action-b"),
            activeResourceLeases = emptySet(),
        )
        assertEquals(listOf("action-a"), ready)
    }

    @Test
    fun deterministic_ready_set_is_stable_under_async_callers() = runTest {
        val workflow = parallelWorkflow()
        val planner = WorkflowReadySetPlanner()
        val results = (0 until 16).map {
            async {
                planner.readyActionNodeIds(
                    definition = workflow,
                    candidateNodeIds = setOf("action-b", "action-a"),
                    receipts = emptyMap(),
                    confirmedNodeIds = setOf("action-a", "action-b"),
                    activeResourceLeases = emptySet(),
                )
            }
        }.awaitAll()
        assertTrue(results.all { it == listOf("action-a", "action-b") })
    }

    @Test
    fun confirmation_bypass_and_completion_edge_mutation_fail_closed() {
        val workflow = parallelWorkflow()
        val admission = WorkflowAdmission().canStart(
            definition = workflow,
            nodeId = "action-a",
            receipts = emptyMap(),
            confirmedNodeIds = emptySet(),
            activeResourceLeases = emptySet(),
        )
        assertEquals(WorkflowAdmissionCode.WAITING_FOR_CONFIRMATION, admission.code)

        val missingCompletion = singleActionWorkflow().copy(edges = emptyList())
        assertEquals(
            WorkflowValidationCode.INVALID_VERIFICATION_GATE,
            WorkflowValidator(catalog()).validate(missingCompletion).code,
        )
    }

    @Test
    fun unknown_never_retries_or_compensates_automatically() {
        val policy = WorkflowRecoveryPolicy()
        val contract = WorkflowRecoveryContract(
            idempotent = true,
            compensationWorkflowId = "compensation-1",
            compensationHumanAdmitted = true,
        )
        assertEquals(
            WorkflowRecoveryDecision.RECONCILIATION_REQUIRED,
            policy.decide(DeviceEffectState.UNKNOWN, contract),
        )
        assertEquals(
            WorkflowRecoveryDecision.RETRY_ADMITTED,
            policy.decide(DeviceEffectState.NONE, contract),
        )
        assertEquals(
            WorkflowRecoveryDecision.COMPENSATION_SEPARATELY_ADMITTED,
            policy.decide(DeviceEffectState.APPLIED, contract),
        )
        assertEquals(
            WorkflowRecoveryDecision.COMPENSATION_REQUIRES_HUMAN_ADMISSION,
            policy.decide(
                DeviceEffectState.APPLIED,
                contract.copy(compensationHumanAdmitted = false),
            ),
        )
    }

    private fun catalog(): DeviceCapabilityCatalog {
        val capability = DeviceCapabilityId("own-webview-actions")
        return DeviceCapabilityCatalog(
            listOf(
                DeviceCapabilityDescriptor(
                    id = capability,
                    canonicalActionIds = setOf("own-webview.click"),
                    actionKinds = setOf(DeviceActionKind.UI_CLICK),
                    allowedProfiles = setOf(DistributionProfile.PLAY_SAFE),
                    scope = DeviceCapabilityScope.OWN_WEBVIEW,
                    privilegeClass = DevicePrivilegeClass.NONE,
                    maximumRisk = DeviceActionRisk.MEDIUM,
                    confirmationClass = DeviceConfirmationClass.USER_CONFIRMATION,
                    verifierId = "webview-postcondition-v1",
                    auditCategory = "device-action",
                ),
            ),
        )
    }

    private fun parallelWorkflow(sharedLease: Boolean = false): WorkflowDefinition {
        val capability = DeviceCapabilityId("own-webview-actions")
        val leaseA = "webview-a"
        val leaseB = if (sharedLease) leaseA else "webview-b"
        return WorkflowDefinition(
            workflowId = "workflow-parallel",
            revision = 1,
            digestSha256 = "a".repeat(64),
            origin = WorkflowOrigin.HUMAN_AUTHORED,
            profile = DistributionProfile.PLAY_SAFE,
            nodes = listOf(
                WorkflowNode.HumanCheckpoint("human-source", "user-input"),
                action("action-a", capability, leaseA),
                action("action-b", capability, leaseB),
                gate("verify-a", "action-a"),
                gate("verify-b", "action-b"),
            ),
            edges = listOf(
                WorkflowEdge("action-a", "verify-a", WorkflowEdgeKind.COMPLETION),
                WorkflowEdge("action-b", "verify-b", WorkflowEdgeKind.COMPLETION),
            ),
            maximumParallelism = 2,
        )
    }

    private fun singleActionWorkflow(): WorkflowDefinition {
        val capability = DeviceCapabilityId("own-webview-actions")
        return WorkflowDefinition(
            workflowId = "workflow-single",
            revision = 1,
            digestSha256 = "e".repeat(64),
            origin = WorkflowOrigin.HUMAN_AUTHORED,
            profile = DistributionProfile.PLAY_SAFE,
            nodes = listOf(action("action-1", capability, "webview-main"), gate("verify-1", "action-1")),
            edges = listOf(WorkflowEdge("action-1", "verify-1", WorkflowEdgeKind.COMPLETION)),
        )
    }

    private fun action(
        id: String,
        capability: DeviceCapabilityId,
        lease: String,
    ) = WorkflowNode.ActionTemplate(
        nodeId = id,
        capabilityId = capability,
        canonicalActionId = "own-webview.click",
        kind = DeviceActionKind.UI_CLICK,
        profile = DistributionProfile.PLAY_SAFE,
        risk = DeviceActionRisk.MEDIUM,
        verifierId = "webview-postcondition-v1",
        requiresConfirmation = true,
        resourceLeases = setOf(lease),
    )

    private fun gate(id: String, actionId: String) = WorkflowNode.VerificationGate(
        nodeId = id,
        actionNodeId = actionId,
        verifierId = "webview-postcondition-v1",
    )
}
