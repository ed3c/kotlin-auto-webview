package dev.ed3c.autowebview.device.workflow

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityCatalog
import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.contract.DeviceActionKind
import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DistributionProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object WorkflowSchema {
    const val VERSION = "kotlin-auto-webview/device-workflow/v1"
    const val MAX_NODES = 128
    const val MAX_EDGES = 512
    const val MAX_FAN_OUT = 16
}

@Serializable
enum class WorkflowOrigin {
    HUMAN_AUTHORED,
    MODEL_PROPOSED,
    IMPORTED,
}

@Serializable
enum class WorkflowValueType {
    TEXT,
    BOOLEAN,
    DIGEST,
    IDENTIFIER,
    EFFECT_STATE,
}

@Serializable
enum class WorkflowTaintClass {
    PUBLIC,
    SANITIZED_UI_METADATA,
    USER_CONTENT,
    PRIVATE_MESSAGE,
    CONTACT_DATA,
    LOCATION,
    PAYMENT,
    PASSWORD,
    SECRET,
    DEVICE_IDENTIFIER,
    PRIVILEGED_RESULT,
}

@Serializable
data class WorkflowFieldRef(
    val nodeId: String,
    val fieldId: String,
    val valueType: WorkflowValueType,
    val taint: WorkflowTaintClass,
) {
    init {
        requireCanonical(nodeId, "source node id")
        requireCanonical(fieldId, "source field id")
    }
}

@Serializable
data class TypedWorkflowBinding(
    val source: WorkflowFieldRef,
    val targetFieldId: String,
    val targetType: WorkflowValueType,
    val admittedTaints: Set<WorkflowTaintClass>,
) {
    init {
        requireCanonical(targetFieldId, "target field id")
        require(source.valueType == targetType) { "Workflow binding type mismatch" }
        require(admittedTaints.isNotEmpty() && admittedTaints.size <= WorkflowTaintClass.entries.size) {
            "Workflow binding taint admission is invalid"
        }
        require(source.taint in admittedTaints) { "Workflow binding would cross an unadmitted taint boundary" }
    }
}

@Serializable
sealed interface WorkflowNode {
    val nodeId: String

    @Serializable
    @SerialName("action")
    data class ActionTemplate(
        override val nodeId: String,
        val capabilityId: DeviceCapabilityId,
        val canonicalActionId: String,
        val kind: DeviceActionKind,
        val profile: DistributionProfile,
        val risk: DeviceActionRisk,
        val verifierId: String,
        val requiresConfirmation: Boolean,
        val resourceLeases: Set<String>,
        val bindings: List<TypedWorkflowBinding> = emptyList(),
    ) : WorkflowNode {
        init {
            requireCanonical(nodeId, "action node id")
            requireCanonical(canonicalActionId, "canonical action id")
            requireCanonical(verifierId, "verifier id")
            require(resourceLeases.size <= 16) { "Action resource leases are unbounded" }
            resourceLeases.forEach { requireCanonical(it, "resource lease") }
            require(bindings.size <= 32) { "Action bindings are unbounded" }
        }
    }

    @Serializable
    @SerialName("human_checkpoint")
    data class HumanCheckpoint(
        override val nodeId: String,
        val checkpointClass: String,
    ) : WorkflowNode {
        init {
            requireCanonical(nodeId, "human checkpoint node id")
            requireCanonical(checkpointClass, "human checkpoint class")
        }
    }

    @Serializable
    @SerialName("verification_gate")
    data class VerificationGate(
        override val nodeId: String,
        val actionNodeId: String,
        val verifierId: String,
    ) : WorkflowNode {
        init {
            requireCanonical(nodeId, "verification gate node id")
            requireCanonical(actionNodeId, "verified action node id")
            requireCanonical(verifierId, "verifier id")
        }
    }
}

@Serializable
enum class WorkflowEdgeKind {
    START,
    COMPLETION,
}

@Serializable
data class WorkflowEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val kind: WorkflowEdgeKind,
) {
    init {
        requireCanonical(fromNodeId, "edge source node id")
        requireCanonical(toNodeId, "edge target node id")
        require(fromNodeId != toNodeId) { "Workflow self-edge is invalid" }
    }
}

@Serializable
data class WorkflowDefinition(
    val schemaVersion: String = WorkflowSchema.VERSION,
    val workflowId: String,
    val revision: Long,
    val digestSha256: String,
    val origin: WorkflowOrigin,
    val profile: DistributionProfile,
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
    val maximumParallelism: Int = 4,
) {
    init {
        require(schemaVersion == WorkflowSchema.VERSION) { "Unknown workflow schema" }
        requireCanonical(workflowId, "workflow id")
        require(revision > 0) { "Workflow revision must be positive" }
        require(digestSha256.matches(Regex("[0-9a-f]{64}"))) { "Workflow digest must be lowercase SHA-256" }
        require(nodes.isNotEmpty() && nodes.size <= WorkflowSchema.MAX_NODES) { "Workflow node count is invalid" }
        require(edges.size <= WorkflowSchema.MAX_EDGES) { "Workflow edge count is invalid" }
        require(maximumParallelism in 1..32) { "Workflow parallelism is outside the bounded range" }
    }
}

@Serializable
enum class WorkflowValidationCode {
    VALID,
    DUPLICATE_NODE,
    DANGLING_EDGE,
    DUPLICATE_EDGE,
    CYCLE,
    FAN_OUT_EXCEEDED,
    PROFILE_MISMATCH,
    PLAY_SAFE_REQUIRES_HUMAN_AUTHORED,
    UNKNOWN_CANONICAL_ACTION,
    CAPABILITY_OWNER_MISMATCH,
    ACTION_KIND_MISMATCH,
    VERIFIER_MISMATCH,
    MISSING_VERIFICATION_GATE,
    INVALID_VERIFICATION_GATE,
    BINDING_SOURCE_MISSING,
}

@Serializable
data class WorkflowValidationResult(
    val code: WorkflowValidationCode,
    val valid: Boolean,
)

class WorkflowValidator(
    private val capabilityCatalog: DeviceCapabilityCatalog,
) {
    fun validate(definition: WorkflowDefinition): WorkflowValidationResult {
        val byId = definition.nodes.associateBy(WorkflowNode::nodeId)
        if (byId.size != definition.nodes.size) return invalid(WorkflowValidationCode.DUPLICATE_NODE)
        if (definition.edges.any { it.fromNodeId !in byId || it.toNodeId !in byId }) {
            return invalid(WorkflowValidationCode.DANGLING_EDGE)
        }
        if (definition.edges.toSet().size != definition.edges.size) return invalid(WorkflowValidationCode.DUPLICATE_EDGE)
        if (hasCycle(definition.nodes.map(WorkflowNode::nodeId), definition.edges)) return invalid(WorkflowValidationCode.CYCLE)
        if (definition.edges.groupingBy(WorkflowEdge::fromNodeId).eachCount().values.any { it > WorkflowSchema.MAX_FAN_OUT }) {
            return invalid(WorkflowValidationCode.FAN_OUT_EXCEEDED)
        }
        if (definition.profile == DistributionProfile.PLAY_SAFE && definition.origin != WorkflowOrigin.HUMAN_AUTHORED) {
            return invalid(WorkflowValidationCode.PLAY_SAFE_REQUIRES_HUMAN_AUTHORED)
        }

        val actions = definition.nodes.filterIsInstance<WorkflowNode.ActionTemplate>()
        for (action in actions) {
            if (action.profile != definition.profile) return invalid(WorkflowValidationCode.PROFILE_MISMATCH)
            val owner = capabilityCatalog.capabilityForCanonicalAction(action.canonicalActionId)
                ?: return invalid(WorkflowValidationCode.UNKNOWN_CANONICAL_ACTION)
            if (owner.id != action.capabilityId) return invalid(WorkflowValidationCode.CAPABILITY_OWNER_MISMATCH)
            if (action.kind !in owner.actionKinds) return invalid(WorkflowValidationCode.ACTION_KIND_MISMATCH)
            if (action.verifierId != owner.verifierId) return invalid(WorkflowValidationCode.VERIFIER_MISMATCH)
            if (action.bindings.any { it.source.nodeId !in byId }) return invalid(WorkflowValidationCode.BINDING_SOURCE_MISSING)

            val gates = definition.nodes.filterIsInstance<WorkflowNode.VerificationGate>().filter { it.actionNodeId == action.nodeId }
            if (gates.size != 1) return invalid(WorkflowValidationCode.MISSING_VERIFICATION_GATE)
            val gate = gates.single()
            if (gate.verifierId != action.verifierId) return invalid(WorkflowValidationCode.INVALID_VERIFICATION_GATE)
            if (WorkflowEdge(action.nodeId, gate.nodeId, WorkflowEdgeKind.COMPLETION) !in definition.edges) {
                return invalid(WorkflowValidationCode.INVALID_VERIFICATION_GATE)
            }
        }
        return WorkflowValidationResult(WorkflowValidationCode.VALID, true)
    }

    private fun hasCycle(nodeIds: List<String>, edges: List<WorkflowEdge>): Boolean {
        val incoming = nodeIds.associateWith { 0 }.toMutableMap()
        edges.forEach { edge -> incoming[edge.toNodeId] = incoming.getValue(edge.toNodeId) + 1 }
        val ready = incoming.filterValues { it == 0 }.keys.toMutableList()
        var visited = 0
        while (ready.isNotEmpty()) {
            val node = ready.removeAt(ready.lastIndex)
            visited += 1
            edges.filter { it.fromNodeId == node }.forEach { edge ->
                val remaining = incoming.getValue(edge.toNodeId) - 1
                incoming[edge.toNodeId] = remaining
                if (remaining == 0) ready += edge.toNodeId
            }
        }
        return visited != nodeIds.size
    }

    private fun invalid(code: WorkflowValidationCode) = WorkflowValidationResult(code, false)
}

@Serializable
data class WorkflowAuthorityToken(
    val tokenId: String,
    val workflowId: String,
    val revision: Long,
    val workflowDigestSha256: String,
    val nodeId: String,
) {
    init {
        requireCanonical(tokenId, "workflow authority token id")
        requireCanonical(workflowId, "workflow id")
        requireCanonical(nodeId, "node id")
        require(revision > 0) { "Workflow token revision must be positive" }
        require(workflowDigestSha256.matches(Regex("[0-9a-f]{64}"))) { "Workflow token digest is invalid" }
    }

    fun isValidFor(definition: WorkflowDefinition): Boolean =
        workflowId == definition.workflowId &&
            revision == definition.revision &&
            workflowDigestSha256 == definition.digestSha256 &&
            definition.nodes.any { it.nodeId == nodeId }
}

@Serializable
data class WorkflowNodeReceipt(
    val workflowId: String,
    val revision: Long,
    val workflowDigestSha256: String,
    val nodeId: String,
    val effectState: DeviceEffectState,
    val evidenceDigestSha256: String,
) {
    init {
        requireCanonical(workflowId, "workflow receipt id")
        requireCanonical(nodeId, "workflow receipt node id")
        require(revision > 0) { "Workflow receipt revision must be positive" }
        require(workflowDigestSha256.matches(Regex("[0-9a-f]{64}"))) { "Workflow receipt digest is invalid" }
        require(evidenceDigestSha256.matches(Regex("[0-9a-f]{64}"))) { "Workflow evidence digest is invalid" }
    }

    fun matches(definition: WorkflowDefinition): Boolean =
        workflowId == definition.workflowId && revision == definition.revision && workflowDigestSha256 == definition.digestSha256
}

@Serializable
enum class WorkflowAdmissionCode {
    READY,
    WAITING_FOR_PREDECESSOR,
    WAITING_FOR_COMPLETION_RECEIPT,
    WAITING_FOR_CONFIRMATION,
    RESOURCE_LEASE_CONFLICT,
    STALE_RECEIPT,
    UNKNOWN_EFFECT_BLOCKS_COMPLETION,
    UNKNOWN_NODE,
}

@Serializable
data class WorkflowAdmissionDecision(
    val code: WorkflowAdmissionCode,
    val ready: Boolean,
)

class WorkflowAdmission {
    fun canStart(
        definition: WorkflowDefinition,
        nodeId: String,
        receipts: Map<String, WorkflowNodeReceipt>,
        confirmedNodeIds: Set<String>,
        activeResourceLeases: Set<String>,
    ): WorkflowAdmissionDecision {
        val node = definition.nodes.firstOrNull { it.nodeId == nodeId }
            ?: return blocked(WorkflowAdmissionCode.UNKNOWN_NODE)
        if (receipts.values.any { !it.matches(definition) }) return blocked(WorkflowAdmissionCode.STALE_RECEIPT)

        val incoming = definition.edges.filter { it.toNodeId == nodeId }
        for (edge in incoming) {
            val receipt = receipts[edge.fromNodeId]
                ?: return blocked(
                    if (edge.kind == WorkflowEdgeKind.COMPLETION) WorkflowAdmissionCode.WAITING_FOR_COMPLETION_RECEIPT
                    else WorkflowAdmissionCode.WAITING_FOR_PREDECESSOR,
                )
            if (edge.kind == WorkflowEdgeKind.COMPLETION && receipt.effectState == DeviceEffectState.UNKNOWN) {
                return blocked(WorkflowAdmissionCode.UNKNOWN_EFFECT_BLOCKS_COMPLETION)
            }
        }

        if (node is WorkflowNode.ActionTemplate) {
            if (node.requiresConfirmation && node.nodeId !in confirmedNodeIds) {
                return blocked(WorkflowAdmissionCode.WAITING_FOR_CONFIRMATION)
            }
            if (node.resourceLeases.any { it in activeResourceLeases }) {
                return blocked(WorkflowAdmissionCode.RESOURCE_LEASE_CONFLICT)
            }
        }
        return WorkflowAdmissionDecision(WorkflowAdmissionCode.READY, true)
    }

    fun canRetry(receipt: WorkflowNodeReceipt, idempotent: Boolean): Boolean =
        receipt.effectState != DeviceEffectState.UNKNOWN && idempotent

    private fun blocked(code: WorkflowAdmissionCode) = WorkflowAdmissionDecision(code, false)
}

private val workflowIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

private fun requireCanonical(value: String, field: String) {
    require(workflowIdentifier.matches(value)) { "$field must be a bounded canonical identifier" }
}
