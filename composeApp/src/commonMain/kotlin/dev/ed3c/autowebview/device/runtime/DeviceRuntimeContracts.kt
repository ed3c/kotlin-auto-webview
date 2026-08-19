package dev.ed3c.autowebview.device.runtime

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.contract.DeviceActionCommand
import dev.ed3c.autowebview.device.contract.DeviceActionProposal
import dev.ed3c.autowebview.device.contract.DeviceActionResult
import dev.ed3c.autowebview.device.contract.DeviceConfirmationReceipt
import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.effects.EffectRecord
import dev.ed3c.autowebview.device.policy.DistributionProfile
import dev.ed3c.autowebview.device.verifier.PostconditionEvidence
import dev.ed3c.autowebview.device.verifier.PreconditionEvidence
import dev.ed3c.autowebview.device.verifier.VerificationIdentity
import dev.ed3c.autowebview.device.workflow.WorkflowDefinition
import dev.ed3c.autowebview.device.workflow.WorkflowNodeReceipt
import dev.ed3c.autowebview.device.workflow.WorkflowRevisionAuthorityBinding
import dev.ed3c.autowebview.dispatcher.DispatcherMode
import dev.ed3c.autowebview.dispatcher.LocalDispatcher

enum class DeviceRuntimeIngress {
    LOCAL_HUMAN,
    MCP_PROPOSAL,
    REMOTE_PROPOSAL,
    MODEL_PROPOSAL,
}

enum class DeviceRuntimeState {
    IDLE,
    PROPOSAL_RECEIVED,
    CANONICAL_ACTION_RESOLVED,
    DISTRIBUTION_PROFILE_CHECKED,
    CAPABILITY_POLICY_EVALUATED,
    CONFIRMATION_REQUIRED,
    HITL_CONFIRMED,
    WORKFLOW_REVISION_FROZEN,
    PRECONDITION_CAPTURED,
    TARGET_RESOLUTION_REQUESTED,
    TARGET_RESOLVED,
    TARGET_NOT_FOUND,
    TARGET_AMBIGUOUS,
    TARGET_STALE,
    FINAL_AUTHORITY_REVALIDATION,
    DISPATCHING_TYPED_COMMAND,
    VERIFYING_POSTCONDITION,
    VERIFIED_APPLIED,
    VERIFIED_NO_EFFECT,
    RECONCILIATION_REQUIRED,
    DENIED,
    CANCELLED_BEFORE_EFFECT,
    AUDIT_COMMITTED,
    AUDIT_COMMIT_FAILED,
    TERMINAL,
}

enum class DeviceRuntimeTerminalCode {
    VERIFIED_APPLIED,
    VERIFIED_NO_EFFECT,
    RECONCILIATION_REQUIRED,
    PROPOSAL_TIME_INVALID,
    CANONICAL_ACTION_MISMATCH,
    PROFILE_MISMATCH,
    POLICY_DENIED,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_INVALID,
    WORKFLOW_INVALID,
    WORKFLOW_BINDING_INVALID,
    WORKFLOW_NOT_READY,
    VERIFICATION_PLAN_UNAVAILABLE,
    PRECONDITION_UNAVAILABLE,
    TARGET_NOT_FOUND,
    TARGET_AMBIGUOUS,
    TARGET_STALE,
    USER_PREEMPTED,
    SCREEN_LOCKED,
    PLATFORM_UNAVAILABLE,
    SUBJECT_CHANGED,
    POLICY_CHANGED,
    WORKFLOW_CHANGED,
    CAPABILITY_REVOKED,
    TARGET_TOKEN_EXPIRED,
    TARGET_BINDING_MISMATCH,
    DISPATCH_NOT_ADMITTED,
    PLATFORM_FAILURE_BEFORE_EFFECT,
    PLATFORM_FAILURE_UNKNOWN,
}

data class DeviceRuntimeTraceEntry(
    val state: DeviceRuntimeState,
    val atEpochMs: Long,
    val detailCode: String,
) {
    init {
        require(atEpochMs >= 0) { "Runtime trace timestamp cannot be negative" }
        requireRuntimeIdentifier(detailCode, "runtime trace detail code")
    }
}

data class DeviceResolvedTarget(
    val subject: DeviceSubjectRef,
    val target: DeviceTargetRef,
    val resolvedTargetToken: String,
    val tokenDigestSha256: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    init {
        requireRuntimeToken(resolvedTargetToken, "resolved target token")
        requireRuntimeDigest(tokenDigestSha256, "resolved target token digest")
        require(issuedAtEpochMs >= 0) { "Target token issue time cannot be negative" }
        require(expiresAtEpochMs >= issuedAtEpochMs) { "Target token expiry precedes issue time" }
    }
}

sealed interface DeviceTargetResolution {
    data class Resolved(val target: DeviceResolvedTarget) : DeviceTargetResolution
    data object NotFound : DeviceTargetResolution
    data object Ambiguous : DeviceTargetResolution
    data class Stale(val reason: String) : DeviceTargetResolution {
        init { requireRuntimeIdentifier(reason, "target stale reason") }
    }
}

fun interface DeviceTargetResolver {
    fun resolve(proposal: DeviceActionProposal, nowEpochMs: Long): DeviceTargetResolution
}

fun interface DevicePreconditionProvider {
    fun capture(identity: VerificationIdentity, nowEpochMs: Long): PreconditionEvidence?
}

fun interface DevicePostconditionProvider {
    fun observe(
        identity: VerificationIdentity,
        precondition: PreconditionEvidence,
        dispatch: DevicePlatformDispatchEvidence,
        nowEpochMs: Long,
    ): PostconditionEvidence
}

data class DeviceDispatchAdmission(
    val authorityEpoch: String,
    val proposalId: String,
    val workflowId: String,
    val workflowRevision: Long,
    val workflowDigestSha256: String,
    val targetTokenDigestSha256: String,
) {
    init {
        requireRuntimeIdentifier(authorityEpoch, "authority epoch")
        requireRuntimeIdentifier(proposalId, "proposal id")
        requireRuntimeIdentifier(workflowId, "workflow id")
        require(workflowRevision > 0) { "Workflow revision must be positive" }
        requireRuntimeDigest(workflowDigestSha256, "workflow digest")
        requireRuntimeDigest(targetTokenDigestSha256, "target token digest")
    }
}

data class DevicePlatformDispatchEvidence(
    val dispatchId: String,
    val platformCallbackAccepted: Boolean,
) {
    init { requireRuntimeToken(dispatchId, "dispatch id") }
}

sealed interface DevicePlatformDispatchResult {
    data class Dispatched(val evidence: DevicePlatformDispatchEvidence) : DevicePlatformDispatchResult
    data class NotDispatched(val code: String) : DevicePlatformDispatchResult {
        init { requireRuntimeIdentifier(code, "not-dispatched code") }
    }
    data class FailureBeforeEffect(val code: String) : DevicePlatformDispatchResult {
        init { requireRuntimeIdentifier(code, "platform failure code") }
    }
    data class FailureUnknown(val code: String) : DevicePlatformDispatchResult {
        init { requireRuntimeIdentifier(code, "platform failure code") }
    }
}

fun interface DevicePlatformDispatcher {
    fun dispatch(command: DeviceActionCommand, admission: DeviceDispatchAdmission): DevicePlatformDispatchResult
}

data class DeviceRuntimeAuthoritySnapshot(
    val authorityEpoch: String,
    val compiledProfile: DistributionProfile,
    val policyVersion: String,
    val workflowId: String,
    val workflowRevision: Long,
    val workflowDigestSha256: String,
    val currentSubject: DeviceSubjectRef,
    val enabledCapabilityIds: Set<DeviceCapabilityId>,
    val userInteractionActive: Boolean,
    val screenLocked: Boolean,
    val platformAvailable: Boolean,
) {
    init {
        requireRuntimeIdentifier(authorityEpoch, "authority epoch")
        requireRuntimeIdentifier(policyVersion, "policy version")
        requireRuntimeIdentifier(workflowId, "workflow id")
        require(workflowRevision > 0) { "Workflow revision must be positive" }
        requireRuntimeDigest(workflowDigestSha256, "workflow digest")
        require(enabledCapabilityIds.size <= 128) { "Enabled capability snapshot is unbounded" }
    }
}

fun interface DeviceRuntimeAuthoritySource {
    fun snapshot(): DeviceRuntimeAuthoritySnapshot
}

class LocalDispatcherDeviceRuntimeAuthoritySource(
    private val dispatcher: LocalDispatcher,
    private val delegate: DeviceRuntimeAuthoritySource,
) : DeviceRuntimeAuthoritySource {
    override fun snapshot(): DeviceRuntimeAuthoritySnapshot {
        val base = delegate.snapshot()
        val dispatcherMode = dispatcher.state.value.mode
        val dispatcherAllowsExecution =
            dispatcherMode == DispatcherMode.READY || dispatcherMode == DispatcherMode.EXECUTING
        return base.copy(
            userInteractionActive = base.userInteractionActive || dispatcherMode == DispatcherMode.OBSERVING_USER,
            platformAvailable = base.platformAvailable && dispatcherAllowsExecution,
        )
    }
}

fun interface DeviceRuntimeClock {
    fun nowEpochMs(): Long
}

data class DeviceRuntimeAuditRecord(
    val proposalId: String,
    val canonicalActionId: String,
    val capabilityId: DeviceCapabilityId,
    val profile: DistributionProfile,
    val workflowId: String,
    val workflowRevision: Long,
    val terminalCode: DeviceRuntimeTerminalCode,
    val effectState: DeviceEffectState,
) {
    init {
        requireRuntimeIdentifier(proposalId, "audit proposal id")
        requireRuntimeIdentifier(canonicalActionId, "audit canonical action id")
        requireRuntimeIdentifier(workflowId, "audit workflow id")
        require(workflowRevision > 0) { "Audit workflow revision must be positive" }
    }
}

fun interface DeviceRuntimeAuditSink {
    fun commit(record: DeviceRuntimeAuditRecord): Boolean
}

data class DeviceRuntimeExecutionRequest(
    val ingress: DeviceRuntimeIngress,
    val proposal: DeviceActionProposal,
    val workflow: WorkflowDefinition,
    val actionNodeId: String,
    val authorityBinding: WorkflowRevisionAuthorityBinding,
    val predecessorReceipts: Map<String, WorkflowNodeReceipt> = emptyMap(),
    val activeResourceLeases: Set<String> = emptySet(),
    val confirmationReceipt: DeviceConfirmationReceipt? = null,
    val idempotencyKey: String,
) {
    init {
        requireRuntimeIdentifier(actionNodeId, "action node id")
        requireRuntimeIdentifier(idempotencyKey, "idempotency key")
        require(predecessorReceipts.size <= 128) { "Predecessor receipt map is unbounded" }
        require(activeResourceLeases.size <= 128) { "Active resource lease set is unbounded" }
    }
}

data class DeviceRuntimeExecutionResult(
    val actionResult: DeviceActionResult,
    val terminalCode: DeviceRuntimeTerminalCode,
    val effectState: DeviceEffectState,
    val trace: List<DeviceRuntimeTraceEntry>,
    val effectRecord: EffectRecord? = null,
    val workflowReceipt: WorkflowNodeReceipt? = null,
    val finalAuthorityBinding: WorkflowRevisionAuthorityBinding? = null,
    val auditCommitted: Boolean,
) {
    init { require(trace.size <= 64) { "Runtime trace exceeds bounded state count" } }
}

data class DeviceRuntimeSourceSubject(
    val headCommit: String,
    val tree: String,
) {
    init {
        require(headCommit.matches(Regex("[0-9a-f]{40}"))) { "Source head must be an exact SHA-1 commit" }
        require(tree.matches(Regex("[0-9a-f]{40}"))) { "Source tree must be an exact SHA-1 tree" }
    }
}

data class DeviceRuntimeConvergenceBinding(
    val androidObservation: DeviceRuntimeSourceSubject,
    val verificationEffects: DeviceRuntimeSourceSubject,
    val workflow: DeviceRuntimeSourceSubject,
) {
    fun matchesSelectedSources(): Boolean = this == DeviceRuntimeConvergenceSubjects.SELECTED
}

object DeviceRuntimeConvergenceSubjects {
    val SELECTED = DeviceRuntimeConvergenceBinding(
        androidObservation = DeviceRuntimeSourceSubject(
            headCommit = "b1c96e129009adc4c83b98a3728a9f1a39850025",
            tree = "90f1a0ca98b5d1e35928abb61be19fe8eae96d9b",
        ),
        verificationEffects = DeviceRuntimeSourceSubject(
            headCommit = "bb5ee8a3973f17990c7e4e7ec99f1475d0b5256d",
            tree = "820e905bd69cff327112d3b379bd5ba72381e7f4",
        ),
        workflow = DeviceRuntimeSourceSubject(
            headCommit = "b2bfb31e0be94190f05b605b36301f9a670a7af6",
            tree = "52fc776e15f7cf3b28a0bb303840ba3b22904c66",
        ),
    )
}

internal val deviceRuntimeIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
internal val deviceRuntimeDigest = Regex("[0-9a-f]{64}")

internal fun requireRuntimeIdentifier(value: String, field: String) {
    require(deviceRuntimeIdentifier.matches(value)) { "$field must be a bounded canonical identifier" }
}

internal fun requireRuntimeDigest(value: String, field: String) {
    require(deviceRuntimeDigest.matches(value)) { "$field must be a lowercase SHA-256 digest" }
}

internal fun requireRuntimeToken(value: String, field: String) {
    require(value.length in 1..256) { "$field is outside the bounded token size" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) { "$field must be opaque and bounded" }
    require(value.none { it in "*?;&|`$<>\\\"'" }) { "$field contains selector or command metacharacters" }
}
