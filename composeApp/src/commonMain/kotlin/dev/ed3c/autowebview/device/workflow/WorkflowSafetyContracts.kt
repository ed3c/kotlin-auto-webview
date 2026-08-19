package dev.ed3c.autowebview.device.workflow

import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.policy.DistributionProfile
import kotlinx.serialization.Serializable

@Serializable
data class WorkflowDeclassificationRule(
    val ruleId: String,
    val version: Int,
    val fromTaint: WorkflowTaintClass,
    val toTaint: WorkflowTaintClass,
    val valueType: WorkflowValueType,
    val transformId: String,
    val allowedProfiles: Set<DistributionProfile>,
) {
    init {
        requireWorkflowCanonical(ruleId, "declassification rule id")
        require(version > 0) { "Declassification rule version must be positive" }
        requireWorkflowCanonical(transformId, "declassification transform id")
        require(allowedProfiles.isNotEmpty()) { "Declassification rule requires an explicit profile ceiling" }
        require(
            fromTaint !in setOf(
                WorkflowTaintClass.PAYMENT,
                WorkflowTaintClass.PASSWORD,
                WorkflowTaintClass.SECRET,
                WorkflowTaintClass.PRIVATE_MESSAGE,
                WorkflowTaintClass.PRIVILEGED_RESULT,
            ),
        ) { "High-sensitivity taint has no declassification path in the workflow engine" }
        require(toTaint in setOf(WorkflowTaintClass.PUBLIC, WorkflowTaintClass.SANITIZED_UI_METADATA)) {
            "Declassification target must be a bounded non-sensitive class"
        }
    }
}

@Serializable
data class WorkflowDeclassificationReceipt(
    val workflowId: String,
    val revision: Long,
    val workflowDigestSha256: String,
    val ruleId: String,
    val ruleVersion: Int,
    val sourceNodeId: String,
    val sourceFieldId: String,
    val inputDigestSha256: String,
    val outputDigestSha256: String,
    val fromTaint: WorkflowTaintClass,
    val toTaint: WorkflowTaintClass,
    val valueType: WorkflowValueType,
) {
    init {
        requireWorkflowCanonical(workflowId, "workflow id")
        require(revision > 0) { "Workflow revision must be positive" }
        requireWorkflowDigest(workflowDigestSha256, "workflow digest")
        requireWorkflowCanonical(ruleId, "declassification rule id")
        require(ruleVersion > 0) { "Declassification rule version must be positive" }
        requireWorkflowCanonical(sourceNodeId, "source node id")
        requireWorkflowCanonical(sourceFieldId, "source field id")
        requireWorkflowDigest(inputDigestSha256, "declassification input digest")
        requireWorkflowDigest(outputDigestSha256, "declassification output digest")
    }

    fun matches(definition: WorkflowDefinition): Boolean =
        workflowId == definition.workflowId &&
            revision == definition.revision &&
            workflowDigestSha256 == definition.digestSha256
}

class WorkflowDeclassificationRegistry(
    rules: List<WorkflowDeclassificationRule>,
) {
    private val rulesByIdentity = rules.associateBy { it.ruleId to it.version }

    init {
        require(rulesByIdentity.size == rules.size) { "Duplicate declassification rule identity" }
    }

    fun declassify(
        definition: WorkflowDefinition,
        source: WorkflowFieldRef,
        ruleId: String,
        ruleVersion: Int,
        inputDigestSha256: String,
        outputDigestSha256: String,
    ): WorkflowDeclassificationReceipt {
        val rule = rulesByIdentity[ruleId to ruleVersion] ?: error("Unknown declassification rule")
        require(definition.profile in rule.allowedProfiles) { "Declassification rule is outside the workflow profile ceiling" }
        require(source.taint == rule.fromTaint) { "Declassification source taint mismatch" }
        require(source.valueType == rule.valueType) { "Declassification value type mismatch" }
        return WorkflowDeclassificationReceipt(
            workflowId = definition.workflowId,
            revision = definition.revision,
            workflowDigestSha256 = definition.digestSha256,
            ruleId = rule.ruleId,
            ruleVersion = rule.version,
            sourceNodeId = source.nodeId,
            sourceFieldId = source.fieldId,
            inputDigestSha256 = inputDigestSha256,
            outputDigestSha256 = outputDigestSha256,
            fromTaint = rule.fromTaint,
            toTaint = rule.toTaint,
            valueType = rule.valueType,
        )
    }
}

@Serializable
data class WorkflowRevisionAuthorityBinding(
    val workflowId: String,
    val revision: Long,
    val workflowDigestSha256: String,
    val nodeId: String,
    val confirmationReceiptId: String? = null,
    val targetTokenDigestSha256: String? = null,
    val idempotencySubject: String? = null,
) {
    init {
        requireWorkflowCanonical(workflowId, "workflow id")
        require(revision > 0) { "Workflow revision must be positive" }
        requireWorkflowDigest(workflowDigestSha256, "workflow digest")
        requireWorkflowCanonical(nodeId, "node id")
        confirmationReceiptId?.let { requireWorkflowCanonical(it, "confirmation receipt id") }
        targetTokenDigestSha256?.let { requireWorkflowDigest(it, "target token digest") }
        idempotencySubject?.let { requireWorkflowCanonical(it, "idempotency subject") }
    }

    fun isValidFor(definition: WorkflowDefinition): Boolean =
        workflowId == definition.workflowId &&
            revision == definition.revision &&
            workflowDigestSha256 == definition.digestSha256 &&
            definition.nodes.any { it.nodeId == nodeId }
}

class WorkflowReadySetPlanner(
    private val admission: WorkflowAdmission = WorkflowAdmission(),
) {
    fun readyActionNodeIds(
        definition: WorkflowDefinition,
        candidateNodeIds: Set<String>,
        receipts: Map<String, WorkflowNodeReceipt>,
        confirmedNodeIds: Set<String>,
        activeResourceLeases: Set<String>,
    ): List<String> {
        val selected = mutableListOf<String>()
        val selectedLeases = activeResourceLeases.toMutableSet()
        for (nodeId in candidateNodeIds.sorted()) {
            if (selected.size >= definition.maximumParallelism) break
            val node = definition.nodes.firstOrNull { it.nodeId == nodeId } as? WorkflowNode.ActionTemplate ?: continue
            val decision = admission.canStart(
                definition = definition,
                nodeId = nodeId,
                receipts = receipts,
                confirmedNodeIds = confirmedNodeIds,
                activeResourceLeases = selectedLeases,
            )
            if (!decision.ready) continue
            selected += nodeId
            selectedLeases += node.resourceLeases
        }
        return selected
    }
}

@Serializable
data class WorkflowRecoveryContract(
    val idempotent: Boolean,
    val compensationWorkflowId: String? = null,
    val compensationHumanAdmitted: Boolean = false,
) {
    init {
        compensationWorkflowId?.let { requireWorkflowCanonical(it, "compensation workflow id") }
        if (compensationHumanAdmitted) {
            require(compensationWorkflowId != null) { "Human compensation admission requires an explicit compensation workflow" }
        }
    }
}

@Serializable
enum class WorkflowRecoveryDecision {
    RETRY_ADMITTED,
    RECONCILIATION_REQUIRED,
    STOP_NO_RETRY,
    COMPENSATION_REQUIRES_HUMAN_ADMISSION,
    COMPENSATION_SEPARATELY_ADMITTED,
}

class WorkflowRecoveryPolicy {
    fun decide(
        effectState: DeviceEffectState,
        contract: WorkflowRecoveryContract,
    ): WorkflowRecoveryDecision = when (effectState) {
        DeviceEffectState.UNKNOWN -> WorkflowRecoveryDecision.RECONCILIATION_REQUIRED
        DeviceEffectState.NONE -> if (contract.idempotent) {
            WorkflowRecoveryDecision.RETRY_ADMITTED
        } else {
            WorkflowRecoveryDecision.STOP_NO_RETRY
        }
        DeviceEffectState.APPLIED -> when {
            contract.compensationWorkflowId == null -> WorkflowRecoveryDecision.STOP_NO_RETRY
            !contract.compensationHumanAdmitted -> WorkflowRecoveryDecision.COMPENSATION_REQUIRES_HUMAN_ADMISSION
            else -> WorkflowRecoveryDecision.COMPENSATION_SEPARATELY_ADMITTED
        }
    }
}

private val workflowSafetyIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val workflowSafetyDigest = Regex("[0-9a-f]{64}")

private fun requireWorkflowCanonical(value: String, field: String) {
    require(workflowSafetyIdentifier.matches(value)) { "$field must be a bounded canonical identifier" }
}

private fun requireWorkflowDigest(value: String, field: String) {
    require(workflowSafetyDigest.matches(value)) { "$field must be a lowercase SHA-256 digest" }
}
