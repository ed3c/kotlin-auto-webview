package dev.ed3c.autowebview.device.runtime

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityCatalog
import dev.ed3c.autowebview.device.contract.DeviceActionCommand
import dev.ed3c.autowebview.device.contract.DeviceActionProposal
import dev.ed3c.autowebview.device.contract.DeviceActionResult
import dev.ed3c.autowebview.device.contract.DeviceConfirmationClass
import dev.ed3c.autowebview.device.contract.DeviceEffectState
import dev.ed3c.autowebview.device.contract.DeviceVerificationEvidence
import dev.ed3c.autowebview.device.contract.DeviceVerifierOutcome
import dev.ed3c.autowebview.device.effects.EffectKey
import dev.ed3c.autowebview.device.effects.EffectLedger
import dev.ed3c.autowebview.device.effects.EffectLedgerEventKind
import dev.ed3c.autowebview.device.effects.EffectRecord
import dev.ed3c.autowebview.device.policy.DeviceActionPolicy
import dev.ed3c.autowebview.device.policy.DeviceActionPolicyDecision
import dev.ed3c.autowebview.device.policy.DistributionProfile
import dev.ed3c.autowebview.device.verifier.PostconditionEvidence
import dev.ed3c.autowebview.device.verifier.VerificationIdentity
import dev.ed3c.autowebview.device.verifier.VerificationRegistry
import dev.ed3c.autowebview.device.verifier.VerificationVerdict
import dev.ed3c.autowebview.device.verifier.VerificationVerdictCode
import dev.ed3c.autowebview.device.workflow.WorkflowAdmission
import dev.ed3c.autowebview.device.workflow.WorkflowDefinition
import dev.ed3c.autowebview.device.workflow.WorkflowNode
import dev.ed3c.autowebview.device.workflow.WorkflowNodeReceipt
import dev.ed3c.autowebview.device.workflow.WorkflowRevisionAuthorityBinding
import dev.ed3c.autowebview.device.workflow.WorkflowValidator

class DeviceAutomationRuntime(
    private val compiledProfile: DistributionProfile,
    private val capabilityCatalog: DeviceCapabilityCatalog,
    private val policy: DeviceActionPolicy,
    private val verificationRegistry: VerificationRegistry,
    private val effectLedger: EffectLedger,
    private val targetResolver: DeviceTargetResolver,
    private val preconditionProvider: DevicePreconditionProvider,
    private val postconditionProvider: DevicePostconditionProvider,
    private val platformDispatcher: DevicePlatformDispatcher,
    private val authoritySource: DeviceRuntimeAuthoritySource,
    private val auditSink: DeviceRuntimeAuditSink,
    private val clock: DeviceRuntimeClock,
    private val workflowValidator: WorkflowValidator = WorkflowValidator(capabilityCatalog),
    private val workflowAdmission: WorkflowAdmission = WorkflowAdmission(),
) {
    fun execute(request: DeviceRuntimeExecutionRequest): DeviceRuntimeExecutionResult {
        val trace = mutableListOf<DeviceRuntimeTraceEntry>()
        fun mark(state: DeviceRuntimeState, detail: String) {
            trace += DeviceRuntimeTraceEntry(state, clock.nowEpochMs(), detail)
        }

        val proposal = request.proposal
        mark(DeviceRuntimeState.PROPOSAL_RECEIVED, "proposal-received")
        if (!proposalTimeValid(proposal, clock.nowEpochMs())) {
            return reject(request, trace, DeviceRuntimeTerminalCode.PROPOSAL_TIME_INVALID, "proposal-time-invalid")
        }

        val descriptor = capabilityCatalog.capabilityForCanonicalAction(proposal.canonicalActionId)
        if (
            descriptor == null ||
            descriptor.id != proposal.capabilityId ||
            proposal.kind !in descriptor.actionKinds ||
            descriptor.auditCategory != proposal.auditCategory
        ) {
            return reject(request, trace, DeviceRuntimeTerminalCode.CANONICAL_ACTION_MISMATCH, "canonical-action-mismatch")
        }
        mark(DeviceRuntimeState.CANONICAL_ACTION_RESOLVED, "canonical-action-resolved")

        if (proposal.profile != compiledProfile || request.workflow.profile != compiledProfile) {
            return reject(request, trace, DeviceRuntimeTerminalCode.PROFILE_MISMATCH, "compiled-profile-mismatch")
        }
        mark(DeviceRuntimeState.DISTRIBUTION_PROFILE_CHECKED, "distribution-profile-checked")

        val initialPolicy = policy.evaluate(proposal)
        mark(DeviceRuntimeState.CAPABILITY_POLICY_EVALUATED, "capability-policy-evaluated")
        when (initialPolicy) {
            is DeviceActionPolicyDecision.Denied -> {
                return reject(
                    request,
                    trace,
                    DeviceRuntimeTerminalCode.POLICY_DENIED,
                    "policy-${canonicalCode(initialPolicy.code.name)}",
                )
            }
            is DeviceActionPolicyDecision.RequiresConfirmation -> {
                mark(DeviceRuntimeState.CONFIRMATION_REQUIRED, "confirmation-required")
                val receipt = request.confirmationReceipt
                    ?: return finalize(
                        request = request,
                        trace = trace,
                        actionResult = DeviceActionResult.UserActionRequired(
                            proposalId = proposal.proposalId,
                            confirmationClass = initialPolicy.confirmationClass,
                        ),
                        terminalCode = DeviceRuntimeTerminalCode.CONFIRMATION_REQUIRED,
                        effectState = DeviceEffectState.NONE,
                    )
                if (!receipt.matches(proposal, clock.nowEpochMs())) {
                    return reject(request, trace, DeviceRuntimeTerminalCode.CONFIRMATION_INVALID, "confirmation-invalid")
                }
                mark(DeviceRuntimeState.HITL_CONFIRMED, "hitl-confirmed")
            }
            DeviceActionPolicyDecision.Allowed -> Unit
        }

        val workflowValidation = workflowValidator.validate(request.workflow)
        val actionNode = request.workflow.nodes.firstOrNull { it.nodeId == request.actionNodeId } as? WorkflowNode.ActionTemplate
        if (!workflowValidation.valid || actionNode == null || !actionMatchesProposal(actionNode, proposal)) {
            return reject(request, trace, DeviceRuntimeTerminalCode.WORKFLOW_INVALID, "workflow-invalid")
        }
        if (!workflowBindingMatches(request, actionNode)) {
            return reject(request, trace, DeviceRuntimeTerminalCode.WORKFLOW_BINDING_INVALID, "workflow-binding-invalid")
        }
        val confirmedNodeIds = if (request.confirmationReceipt != null) setOf(actionNode.nodeId) else emptySet()
        val admission = workflowAdmission.canStart(
            definition = request.workflow,
            nodeId = actionNode.nodeId,
            receipts = request.predecessorReceipts,
            confirmedNodeIds = confirmedNodeIds,
            activeResourceLeases = request.activeResourceLeases,
        )
        if (!admission.ready) {
            return reject(
                request,
                trace,
                DeviceRuntimeTerminalCode.WORKFLOW_NOT_READY,
                "workflow-${canonicalCode(admission.code.name)}",
            )
        }
        mark(DeviceRuntimeState.WORKFLOW_REVISION_FROZEN, "workflow-revision-frozen")

        // Local Dispatcher/user authority outranks observation itself. Do not capture a
        // precondition or resolve a target if the current authority snapshot is already stale,
        // user-owned, locked, unavailable, profile-widened, policy-moved or workflow-moved.
        val preObservationAuthority = authoritySource.snapshot()
        val preObservationNow = clock.nowEpochMs()
        val preObservationInvalidation = revalidateAuthorityContext(
            request = request,
            authority = preObservationAuthority,
            nowEpochMs = preObservationNow,
        )
        if (preObservationInvalidation != null) {
            mark(DeviceRuntimeState.CANCELLED_BEFORE_EFFECT, "pre-observation-${canonicalCode(preObservationInvalidation.name)}")
            return finalize(
                request = request,
                trace = trace,
                actionResult = DeviceActionResult.CancelledBeforeEffect(proposal.proposalId),
                terminalCode = preObservationInvalidation,
                effectState = DeviceEffectState.NONE,
            )
        }

        val plan = verificationRegistry.plans.filter { candidate ->
            candidate.current &&
                candidate.key.capabilityId == proposal.capabilityId &&
                candidate.key.canonicalActionId == proposal.canonicalActionId &&
                candidate.key.verifierId == proposal.verifierId
        }.singleOrNull() ?: return reject(
            request,
            trace,
            DeviceRuntimeTerminalCode.VERIFICATION_PLAN_UNAVAILABLE,
            "verification-plan-unavailable",
        )
        val verificationIdentity = VerificationIdentity(
            proposalId = proposal.proposalId,
            capabilityId = proposal.capabilityId,
            canonicalActionId = proposal.canonicalActionId,
            subject = proposal.subject,
            target = proposal.target,
            verifierId = proposal.verifierId,
            verifierVersion = plan.key.verifierVersion,
        )
        val precondition = preconditionProvider.capture(verificationIdentity, clock.nowEpochMs())
            ?: return reject(
                request,
                trace,
                DeviceRuntimeTerminalCode.PRECONDITION_UNAVAILABLE,
                "precondition-unavailable",
            )
        if (precondition.identity != verificationIdentity) {
            return reject(
                request,
                trace,
                DeviceRuntimeTerminalCode.PRECONDITION_UNAVAILABLE,
                "precondition-identity-mismatch",
            )
        }
        mark(DeviceRuntimeState.PRECONDITION_CAPTURED, "precondition-captured")

        mark(DeviceRuntimeState.TARGET_RESOLUTION_REQUESTED, "target-resolution-requested")
        val resolved = when (val resolution = targetResolver.resolve(proposal, clock.nowEpochMs())) {
            DeviceTargetResolution.NotFound -> {
                mark(DeviceRuntimeState.TARGET_NOT_FOUND, "target-not-found")
                return reject(request, trace, DeviceRuntimeTerminalCode.TARGET_NOT_FOUND, "target-not-found")
            }
            DeviceTargetResolution.Ambiguous -> {
                mark(DeviceRuntimeState.TARGET_AMBIGUOUS, "target-ambiguous")
                return reject(request, trace, DeviceRuntimeTerminalCode.TARGET_AMBIGUOUS, "target-ambiguous")
            }
            is DeviceTargetResolution.Stale -> {
                mark(DeviceRuntimeState.TARGET_STALE, resolution.reason)
                return reject(request, trace, DeviceRuntimeTerminalCode.TARGET_STALE, "target-stale")
            }
            is DeviceTargetResolution.Resolved -> resolution.target
        }
        if (resolved.subject != proposal.subject || resolved.target != proposal.target) {
            mark(DeviceRuntimeState.TARGET_STALE, "target-binding-mismatch")
            return reject(request, trace, DeviceRuntimeTerminalCode.TARGET_BINDING_MISMATCH, "target-binding-mismatch")
        }
        mark(DeviceRuntimeState.TARGET_RESOLVED, "target-resolved")

        mark(DeviceRuntimeState.FINAL_AUTHORITY_REVALIDATION, "final-authority-revalidation")
        val authority = authoritySource.snapshot()
        val finalNow = clock.nowEpochMs()
        val invalidation = revalidateFinalAuthority(request, resolved, authority, finalNow)
        if (invalidation != null) {
            mark(DeviceRuntimeState.CANCELLED_BEFORE_EFFECT, canonicalCode(invalidation.name))
            return finalize(
                request = request,
                trace = trace,
                actionResult = DeviceActionResult.CancelledBeforeEffect(proposal.proposalId),
                terminalCode = invalidation,
                effectState = DeviceEffectState.NONE,
            )
        }
        val finalPolicy = policy.evaluate(proposal)
        if (finalPolicy is DeviceActionPolicyDecision.Denied) {
            mark(DeviceRuntimeState.CANCELLED_BEFORE_EFFECT, "capability-revoked")
            return finalize(
                request = request,
                trace = trace,
                actionResult = DeviceActionResult.CancelledBeforeEffect(proposal.proposalId),
                terminalCode = DeviceRuntimeTerminalCode.CAPABILITY_REVOKED,
                effectState = DeviceEffectState.NONE,
            )
        }
        if (finalPolicy is DeviceActionPolicyDecision.RequiresConfirmation) {
            val receipt = request.confirmationReceipt
            if (receipt == null || !receipt.matches(proposal, finalNow)) {
                mark(DeviceRuntimeState.CANCELLED_BEFORE_EFFECT, "confirmation-expired")
                return finalize(
                    request = request,
                    trace = trace,
                    actionResult = DeviceActionResult.CancelledBeforeEffect(proposal.proposalId),
                    terminalCode = DeviceRuntimeTerminalCode.CONFIRMATION_INVALID,
                    effectState = DeviceEffectState.NONE,
                )
            }
        }

        val finalBinding = request.authorityBinding.copy(targetTokenDigestSha256 = resolved.tokenDigestSha256)
        val effectKey = EffectKey(
            proposalId = proposal.proposalId,
            canonicalActionId = proposal.canonicalActionId,
            idempotencyKey = request.idempotencyKey,
        )
        effectLedger.open(effectKey)
        var effectRecord = effectLedger.apply(
            key = effectKey,
            eventId = "${proposal.proposalId}.precondition",
            event = EffectLedgerEventKind.PRECONDITION_CAPTURED,
            atEpochMs = precondition.observedAtEpochMs,
        )
        effectRecord = effectLedger.apply(
            key = effectKey,
            eventId = "${proposal.proposalId}.dispatch-admitted",
            event = EffectLedgerEventKind.DISPATCH_ADMITTED,
            atEpochMs = finalNow,
        )
        effectRecord = effectLedger.apply(
            key = effectKey,
            eventId = "${proposal.proposalId}.dispatch-started",
            event = EffectLedgerEventKind.DISPATCH_STARTED,
            atEpochMs = finalNow,
        )

        val command = DeviceActionCommand(
            proposalId = proposal.proposalId,
            canonicalActionId = proposal.canonicalActionId,
            capabilityId = proposal.capabilityId,
            profile = proposal.profile,
            subject = proposal.subject,
            target = proposal.target,
            resolvedTargetToken = resolved.resolvedTargetToken,
            kind = proposal.kind,
            payload = proposal.payload,
            payloadDigestSha256 = proposal.payloadDigestSha256,
            verifierId = proposal.verifierId,
            policyVersion = proposal.policyVersion,
            confirmationReceiptId = request.confirmationReceipt?.receiptId,
        )
        val dispatchAdmission = DeviceDispatchAdmission(
            authorityEpoch = authority.authorityEpoch,
            proposalId = proposal.proposalId,
            workflowId = request.workflow.workflowId,
            workflowRevision = request.workflow.revision,
            workflowDigestSha256 = request.workflow.digestSha256,
            targetTokenDigestSha256 = resolved.tokenDigestSha256,
        )
        mark(DeviceRuntimeState.DISPATCHING_TYPED_COMMAND, "dispatching-typed-command")
        return when (val dispatched = platformDispatcher.dispatch(command, dispatchAdmission)) {
            is DevicePlatformDispatchResult.NotDispatched -> {
                effectRecord = effectLedger.apply(
                    key = effectKey,
                    eventId = "${proposal.proposalId}.not-dispatched",
                    event = EffectLedgerEventKind.NOT_DISPATCHED,
                    atEpochMs = clock.nowEpochMs(),
                )
                mark(DeviceRuntimeState.CANCELLED_BEFORE_EFFECT, dispatched.code)
                finalize(
                    request = request,
                    trace = trace,
                    actionResult = DeviceActionResult.CancelledBeforeEffect(proposal.proposalId),
                    terminalCode = DeviceRuntimeTerminalCode.DISPATCH_NOT_ADMITTED,
                    effectState = DeviceEffectState.NONE,
                    effectRecord = effectRecord,
                    finalAuthorityBinding = finalBinding,
                )
            }
            is DevicePlatformDispatchResult.FailureBeforeEffect -> {
                effectRecord = effectLedger.apply(
                    key = effectKey,
                    eventId = "${proposal.proposalId}.platform-failure-before-effect",
                    event = EffectLedgerEventKind.PLATFORM_FAILURE_BEFORE_EFFECT,
                    atEpochMs = clock.nowEpochMs(),
                )
                mark(DeviceRuntimeState.CANCELLED_BEFORE_EFFECT, dispatched.code)
                finalize(
                    request = request,
                    trace = trace,
                    actionResult = DeviceActionResult.CancelledBeforeEffect(proposal.proposalId),
                    terminalCode = DeviceRuntimeTerminalCode.PLATFORM_FAILURE_BEFORE_EFFECT,
                    effectState = DeviceEffectState.NONE,
                    effectRecord = effectRecord,
                    finalAuthorityBinding = finalBinding,
                )
            }
            is DevicePlatformDispatchResult.FailureUnknown -> {
                effectRecord = effectLedger.apply(
                    key = effectKey,
                    eventId = "${proposal.proposalId}.platform-failure-unknown",
                    event = EffectLedgerEventKind.PLATFORM_FAILURE_UNKNOWN,
                    atEpochMs = clock.nowEpochMs(),
                )
                mark(DeviceRuntimeState.RECONCILIATION_REQUIRED, dispatched.code)
                finalize(
                    request = request,
                    trace = trace,
                    actionResult = DeviceActionResult.FailedUnknownEffect(
                        proposalId = proposal.proposalId,
                        code = "platform-failure-unknown",
                    ),
                    terminalCode = DeviceRuntimeTerminalCode.PLATFORM_FAILURE_UNKNOWN,
                    effectState = DeviceEffectState.UNKNOWN,
                    effectRecord = effectRecord,
                    finalAuthorityBinding = finalBinding,
                )
            }
            is DevicePlatformDispatchResult.Dispatched -> verifyDispatched(
                request = request,
                trace = trace,
                identity = verificationIdentity,
                precondition = precondition,
                planKey = plan.key,
                effectKey = effectKey,
                initialEffectRecord = effectRecord,
                dispatch = dispatched.evidence,
                dispatchAtEpochMs = clock.nowEpochMs(),
                finalAuthorityBinding = finalBinding,
            )
        }
    }

    private fun verifyDispatched(
        request: DeviceRuntimeExecutionRequest,
        trace: MutableList<DeviceRuntimeTraceEntry>,
        identity: VerificationIdentity,
        precondition: dev.ed3c.autowebview.device.verifier.PreconditionEvidence,
        planKey: dev.ed3c.autowebview.device.verifier.VerificationPlanKey,
        effectKey: EffectKey,
        initialEffectRecord: EffectRecord,
        dispatch: DevicePlatformDispatchEvidence,
        dispatchAtEpochMs: Long,
        finalAuthorityBinding: WorkflowRevisionAuthorityBinding,
    ): DeviceRuntimeExecutionResult {
        val proposal = request.proposal
        var effectRecord = effectLedger.apply(
            key = effectKey,
            eventId = "${proposal.proposalId}.dispatched",
            event = EffectLedgerEventKind.DISPATCHED,
            atEpochMs = dispatchAtEpochMs,
        )
        trace += DeviceRuntimeTraceEntry(
            DeviceRuntimeState.VERIFYING_POSTCONDITION,
            clock.nowEpochMs(),
            "verifying-postcondition",
        )
        val postcondition = postconditionProvider.observe(identity, precondition, dispatch, clock.nowEpochMs())
        val verifyNow = clock.nowEpochMs()
        val verdict = if (postcondition.observedAtEpochMs < dispatchAtEpochMs) {
            VerificationVerdict(
                code = VerificationVerdictCode.INVALID_TIME_ORDER,
                effectState = DeviceEffectState.UNKNOWN,
                reconciliationRequired = true,
                evidenceDigestSha256 = postcondition.evidenceDigestSha256,
            )
        } else {
            verificationRegistry.verify(planKey, precondition, postcondition, verifyNow)
        }
        val ledgerEvent = when (verdict.code) {
            VerificationVerdictCode.APPLIED -> EffectLedgerEventKind.POSTCONDITION_APPLIED
            VerificationVerdictCode.NO_EFFECT -> EffectLedgerEventKind.POSTCONDITION_NO_EFFECT
            VerificationVerdictCode.OBSERVER_LOST -> EffectLedgerEventKind.OBSERVER_LOST
            VerificationVerdictCode.CONTRADICTORY_EVIDENCE -> EffectLedgerEventKind.CONTRADICTORY_EVIDENCE
            else -> EffectLedgerEventKind.POSTCONDITION_INCONCLUSIVE
        }
        effectRecord = effectLedger.apply(
            key = effectKey,
            eventId = "${proposal.proposalId}.postcondition",
            event = ledgerEvent,
            atEpochMs = maxOf(verifyNow, dispatchAtEpochMs),
            verification = verdict,
        )
        val evidence = verificationEvidence(postcondition, verdict)
        val workflowReceipt = WorkflowNodeReceipt(
            workflowId = request.workflow.workflowId,
            revision = request.workflow.revision,
            workflowDigestSha256 = request.workflow.digestSha256,
            nodeId = request.actionNodeId,
            effectState = verdict.effectState,
            evidenceDigestSha256 = postcondition.evidenceDigestSha256,
        )
        return when (verdict.effectState) {
            DeviceEffectState.APPLIED -> {
                trace += DeviceRuntimeTraceEntry(DeviceRuntimeState.VERIFIED_APPLIED, clock.nowEpochMs(), "verified-applied")
                finalize(
                    request = request,
                    trace = trace,
                    actionResult = DeviceActionResult.VerifiedApplied(proposal.proposalId, evidence),
                    terminalCode = DeviceRuntimeTerminalCode.VERIFIED_APPLIED,
                    effectState = DeviceEffectState.APPLIED,
                    effectRecord = effectRecord,
                    workflowReceipt = workflowReceipt,
                    finalAuthorityBinding = finalAuthorityBinding,
                )
            }
            DeviceEffectState.NONE -> {
                trace += DeviceRuntimeTraceEntry(DeviceRuntimeState.VERIFIED_NO_EFFECT, clock.nowEpochMs(), "verified-no-effect")
                finalize(
                    request = request,
                    trace = trace,
                    actionResult = DeviceActionResult.VerifiedNoEffect(proposal.proposalId, evidence),
                    terminalCode = DeviceRuntimeTerminalCode.VERIFIED_NO_EFFECT,
                    effectState = DeviceEffectState.NONE,
                    effectRecord = effectRecord,
                    workflowReceipt = workflowReceipt,
                    finalAuthorityBinding = finalAuthorityBinding,
                )
            }
            DeviceEffectState.UNKNOWN -> {
                trace += DeviceRuntimeTraceEntry(
                    DeviceRuntimeState.RECONCILIATION_REQUIRED,
                    clock.nowEpochMs(),
                    "verification-${canonicalCode(verdict.code.name)}",
                )
                finalize(
                    request = request,
                    trace = trace,
                    actionResult = DeviceActionResult.FailedUnknownEffect(
                        proposalId = proposal.proposalId,
                        code = "verification-${canonicalCode(verdict.code.name)}",
                        evidence = evidence,
                    ),
                    terminalCode = DeviceRuntimeTerminalCode.RECONCILIATION_REQUIRED,
                    effectState = DeviceEffectState.UNKNOWN,
                    effectRecord = effectRecord,
                    workflowReceipt = workflowReceipt,
                    finalAuthorityBinding = finalAuthorityBinding,
                )
            }
        }
    }

    private fun verificationEvidence(
        postcondition: PostconditionEvidence,
        verdict: VerificationVerdict,
    ): DeviceVerificationEvidence = DeviceVerificationEvidence(
        verifierId = postcondition.identity.verifierId,
        subject = postcondition.identity.subject,
        target = postcondition.identity.target,
        observedAtEpochMs = postcondition.observedAtEpochMs,
        outcome = when (verdict.effectState) {
            DeviceEffectState.APPLIED -> DeviceVerifierOutcome.APPLIED
            DeviceEffectState.NONE -> DeviceVerifierOutcome.NO_EFFECT
            DeviceEffectState.UNKNOWN -> DeviceVerifierOutcome.INCONCLUSIVE
        },
        evidenceDigestSha256 = postcondition.evidenceDigestSha256,
    )

    private fun actionMatchesProposal(
        action: WorkflowNode.ActionTemplate,
        proposal: DeviceActionProposal,
    ): Boolean =
        action.capabilityId == proposal.capabilityId &&
            action.canonicalActionId == proposal.canonicalActionId &&
            action.kind == proposal.kind &&
            action.profile == proposal.profile &&
            action.risk == proposal.risk &&
            action.verifierId == proposal.verifierId &&
            action.requiresConfirmation == (proposal.confirmationClass != DeviceConfirmationClass.NONE)

    private fun workflowBindingMatches(
        request: DeviceRuntimeExecutionRequest,
        action: WorkflowNode.ActionTemplate,
    ): Boolean {
        val binding = request.authorityBinding
        if (!binding.isValidFor(request.workflow) || binding.nodeId != action.nodeId) return false
        if (binding.idempotencySubject != request.idempotencyKey) return false
        val receiptId = request.confirmationReceipt?.receiptId
        if (action.requiresConfirmation && binding.confirmationReceiptId != receiptId) return false
        if (!action.requiresConfirmation && binding.confirmationReceiptId != null) return false
        return true
    }

    private fun revalidateAuthorityContext(
        request: DeviceRuntimeExecutionRequest,
        authority: DeviceRuntimeAuthoritySnapshot,
        nowEpochMs: Long,
    ): DeviceRuntimeTerminalCode? {
        val proposal = request.proposal
        if (!proposalTimeValid(proposal, nowEpochMs)) return DeviceRuntimeTerminalCode.PROPOSAL_TIME_INVALID
        if (authority.userInteractionActive) return DeviceRuntimeTerminalCode.USER_PREEMPTED
        if (authority.screenLocked) return DeviceRuntimeTerminalCode.SCREEN_LOCKED
        if (!authority.platformAvailable) return DeviceRuntimeTerminalCode.PLATFORM_UNAVAILABLE
        if (authority.compiledProfile != compiledProfile || proposal.profile != authority.compiledProfile) {
            return DeviceRuntimeTerminalCode.PROFILE_MISMATCH
        }
        if (authority.policyVersion != proposal.policyVersion) return DeviceRuntimeTerminalCode.POLICY_CHANGED
        if (
            authority.workflowId != request.workflow.workflowId ||
            authority.workflowRevision != request.workflow.revision ||
            authority.workflowDigestSha256 != request.workflow.digestSha256
        ) {
            return DeviceRuntimeTerminalCode.WORKFLOW_CHANGED
        }
        if (authority.currentSubject != proposal.subject) return DeviceRuntimeTerminalCode.SUBJECT_CHANGED
        if (proposal.capabilityId !in authority.enabledCapabilityIds) return DeviceRuntimeTerminalCode.CAPABILITY_REVOKED
        if (!request.authorityBinding.isValidFor(request.workflow)) return DeviceRuntimeTerminalCode.WORKFLOW_CHANGED
        return null
    }

    private fun revalidateFinalAuthority(
        request: DeviceRuntimeExecutionRequest,
        resolved: DeviceResolvedTarget,
        authority: DeviceRuntimeAuthoritySnapshot,
        nowEpochMs: Long,
    ): DeviceRuntimeTerminalCode? {
        revalidateAuthorityContext(request, authority, nowEpochMs)?.let { return it }
        val proposal = request.proposal
        if (nowEpochMs < resolved.issuedAtEpochMs || nowEpochMs > resolved.expiresAtEpochMs) {
            return DeviceRuntimeTerminalCode.TARGET_TOKEN_EXPIRED
        }
        if (resolved.subject != proposal.subject || resolved.target != proposal.target) {
            return DeviceRuntimeTerminalCode.TARGET_BINDING_MISMATCH
        }
        val preboundDigest = request.authorityBinding.targetTokenDigestSha256
        if (preboundDigest != null && preboundDigest != resolved.tokenDigestSha256) {
            return DeviceRuntimeTerminalCode.TARGET_BINDING_MISMATCH
        }
        return null
    }

    private fun proposalTimeValid(proposal: DeviceActionProposal, nowEpochMs: Long): Boolean =
        nowEpochMs >= proposal.createdAtEpochMs && nowEpochMs <= proposal.expiresAtEpochMs

    private fun reject(
        request: DeviceRuntimeExecutionRequest,
        trace: MutableList<DeviceRuntimeTraceEntry>,
        terminalCode: DeviceRuntimeTerminalCode,
        detail: String,
    ): DeviceRuntimeExecutionResult {
        trace += DeviceRuntimeTraceEntry(DeviceRuntimeState.DENIED, clock.nowEpochMs(), detail)
        return finalize(
            request = request,
            trace = trace,
            actionResult = DeviceActionResult.Rejected(
                proposalId = request.proposal.proposalId,
                code = canonicalCode(terminalCode.name),
                message = detail.replace('-', ' ').take(512),
            ),
            terminalCode = terminalCode,
            effectState = DeviceEffectState.NONE,
        )
    }

    private fun finalize(
        request: DeviceRuntimeExecutionRequest,
        trace: MutableList<DeviceRuntimeTraceEntry>,
        actionResult: DeviceActionResult,
        terminalCode: DeviceRuntimeTerminalCode,
        effectState: DeviceEffectState,
        effectRecord: EffectRecord? = null,
        workflowReceipt: WorkflowNodeReceipt? = null,
        finalAuthorityBinding: WorkflowRevisionAuthorityBinding? = null,
    ): DeviceRuntimeExecutionResult {
        val audit = DeviceRuntimeAuditRecord(
            proposalId = request.proposal.proposalId,
            canonicalActionId = request.proposal.canonicalActionId,
            capabilityId = request.proposal.capabilityId,
            profile = request.proposal.profile,
            workflowId = request.workflow.workflowId,
            workflowRevision = request.workflow.revision,
            terminalCode = terminalCode,
            effectState = effectState,
        )
        val auditCommitted = runCatching { auditSink.commit(audit) }.getOrDefault(false)
        trace += DeviceRuntimeTraceEntry(
            state = if (auditCommitted) DeviceRuntimeState.AUDIT_COMMITTED else DeviceRuntimeState.AUDIT_COMMIT_FAILED,
            atEpochMs = clock.nowEpochMs(),
            detailCode = if (auditCommitted) "audit-committed" else "audit-commit-failed",
        )
        trace += DeviceRuntimeTraceEntry(DeviceRuntimeState.TERMINAL, clock.nowEpochMs(), "terminal")
        return DeviceRuntimeExecutionResult(
            actionResult = actionResult,
            terminalCode = terminalCode,
            effectState = effectState,
            trace = trace.toList(),
            effectRecord = effectRecord,
            workflowReceipt = workflowReceipt,
            finalAuthorityBinding = finalAuthorityBinding,
            auditCommitted = auditCommitted,
        )
    }

    private fun canonicalCode(value: String): String = value.lowercase().replace('_', '-')
}
