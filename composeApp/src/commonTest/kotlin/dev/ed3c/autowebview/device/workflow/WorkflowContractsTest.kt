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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowContractsTest {
    private val capabilityId = DeviceCapabilityId("own-webview-actions")
    private val catalog = DeviceCapabilityCatalog(
        listOf(
            DeviceCapabilityDescriptor(
                id = capabilityId,
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

    @Test
    fun valid_human_authored_play_safe_workflow_has_exact_verification_gate() {
        val result = WorkflowValidator(catalog).validate(definition())
        assertEquals(WorkflowValidationCode.VALID, result.code)
        assertTrue(result.valid)
    }

    @Test
    fun semantic_alias_and_model_frozen_play_safe_workflow_fail_closed() {
        val alias = definition(
            nodes = listOf(action().copy(canonicalActionId = "click"), gate()),
        )
        assertEquals(WorkflowValidationCode.UNKNOWN_CANONICAL_ACTION, WorkflowValidator(catalog).validate(alias).code)

        val modelFrozen = definition().copy(origin = WorkflowOrigin.MODEL_PROPOSED)
        assertEquals(
            WorkflowValidationCode.PLAY_SAFE_REQUIRES_HUMAN_AUTHORED,
            WorkflowValidator(catalog).validate(modelFrozen).code,
        )
    }

    @Test
    fun cycles_and_missing_verification_edges_are_rejected() {
        val cyclic = definition(
            edges = listOf(
                WorkflowEdge("action-1", "verify-1", WorkflowEdgeKind.COMPLETION),
                WorkflowEdge("verify-1", "action-1", WorkflowEdgeKind.START),
            ),
        )
        assertEquals(WorkflowValidationCode.CYCLE, WorkflowValidator(catalog).validate(cyclic).code)

        val missingGateEdge = definition(edges = emptyList())
        assertEquals(
            WorkflowValidationCode.INVALID_VERIFICATION_GATE,
            WorkflowValidator(catalog).validate(missingGateEdge).code,
        )
    }

    @Test
    fun typed_binding_rejects_type_or_taint_smuggling() {
        val source = WorkflowFieldRef("human-1", "secret", WorkflowValueType.TEXT, WorkflowTaintClass.SECRET)
        assertFailsWith<IllegalArgumentException> {
            TypedWorkflowBinding(source, "target", WorkflowValueType.IDENTIFIER, setOf(WorkflowTaintClass.SECRET))
        }
        assertFailsWith<IllegalArgumentException> {
            TypedWorkflowBinding(source, "target", WorkflowValueType.TEXT, setOf(WorkflowTaintClass.PUBLIC))
        }
    }

    @Test
    fun revision_change_revokes_authority_token_and_old_receipt() {
        val original = definition()
        val token = WorkflowAuthorityToken("token-1", original.workflowId, original.revision, original.digestSha256, "action-1")
        assertTrue(token.isValidFor(original))
        val revised = original.copy(revision = 2, digestSha256 = "b".repeat(64))
        assertFalse(token.isValidFor(revised))

        val staleReceipt = receipt(original, "action-1", DeviceEffectState.APPLIED)
        val admission = WorkflowAdmission().canStart(
            revised,
            "verify-1",
            receipts = mapOf("action-1" to staleReceipt),
            confirmedNodeIds = emptySet(),
            activeResourceLeases = emptySet(),
        )
        assertEquals(WorkflowAdmissionCode.STALE_RECEIPT, admission.code)
    }

    @Test
    fun unknown_effect_and_resource_conflict_block_progress() {
        val workflow = definition()
        val unknown = WorkflowAdmission().canStart(
            workflow,
            "verify-1",
            receipts = mapOf("action-1" to receipt(workflow, "action-1", DeviceEffectState.UNKNOWN)),
            confirmedNodeIds = emptySet(),
            activeResourceLeases = emptySet(),
        )
        assertEquals(WorkflowAdmissionCode.UNKNOWN_EFFECT_BLOCKS_COMPLETION, unknown.code)

        val blockedAction = WorkflowAdmission().canStart(
            workflow,
            "action-1",
            receipts = emptyMap(),
            confirmedNodeIds = setOf("action-1"),
            activeResourceLeases = setOf("webview-main"),
        )
        assertEquals(WorkflowAdmissionCode.RESOURCE_LEASE_CONFLICT, blockedAction.code)
        assertFalse(WorkflowAdmission().canRetry(receipt(workflow, "action-1", DeviceEffectState.UNKNOWN), idempotent = true))
    }

    private fun definition(
        nodes: List<WorkflowNode> = listOf(action(), gate()),
        edges: List<WorkflowEdge> = listOf(WorkflowEdge("action-1", "verify-1", WorkflowEdgeKind.COMPLETION)),
    ) = WorkflowDefinition(
        workflowId = "workflow-1",
        revision = 1,
        digestSha256 = "a".repeat(64),
        origin = WorkflowOrigin.HUMAN_AUTHORED,
        profile = DistributionProfile.PLAY_SAFE,
        nodes = nodes,
        edges = edges,
    )

    private fun action() = WorkflowNode.ActionTemplate(
        nodeId = "action-1",
        capabilityId = capabilityId,
        canonicalActionId = "own-webview.click",
        kind = DeviceActionKind.UI_CLICK,
        profile = DistributionProfile.PLAY_SAFE,
        risk = DeviceActionRisk.MEDIUM,
        verifierId = "webview-postcondition-v1",
        requiresConfirmation = true,
        resourceLeases = setOf("webview-main"),
    )

    private fun gate() = WorkflowNode.VerificationGate(
        nodeId = "verify-1",
        actionNodeId = "action-1",
        verifierId = "webview-postcondition-v1",
    )

    private fun receipt(
        definition: WorkflowDefinition,
        nodeId: String,
        effect: DeviceEffectState,
    ) = WorkflowNodeReceipt(
        workflowId = definition.workflowId,
        revision = definition.revision,
        workflowDigestSha256 = definition.digestSha256,
        nodeId = nodeId,
        effectState = effect,
        evidenceDigestSha256 = "c".repeat(64),
    )
}
