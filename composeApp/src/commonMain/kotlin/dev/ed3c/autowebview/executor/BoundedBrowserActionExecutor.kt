package dev.ed3c.autowebview.executor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class BoundedBrowserActionExecutor(
    private val platform: BrowserActionPlatform,
) {
    suspend fun execute(
        proposal: BrowserActionProposal,
        context: BrowserActionExecutionContext,
    ): BrowserActionExecutionResult {
        val trace = mutableListOf<BrowserExecutionTraceEntry>()
        fun mark(state: BrowserExecutionState) {
            trace += BrowserExecutionTraceEntry(state, context.nowEpochMs)
        }

        fun reject(
            code: BrowserExecutionFailureCode,
            message: String,
        ): BrowserActionExecutionResult.Rejected {
            mark(BrowserExecutionState.REJECTED)
            return BrowserActionExecutionResult.Rejected(
                proposalId = proposal.id,
                code = code,
                message = message,
                trace = trace.toList(),
            )
        }

        mark(BrowserExecutionState.VALIDATING_AUTHORITY)
        if (context.userInteraction.isUserInteracting()) {
            return reject(
                BrowserExecutionFailureCode.USER_PREEMPTED,
                "User interaction has priority over agent execution",
            )
        }
        if (context.dispatcher.mode != dev.ed3c.autowebview.dispatcher.DispatcherMode.EXECUTING) {
            return reject(
                BrowserExecutionFailureCode.DISPATCHER_NOT_EXECUTING,
                "Dispatcher has not admitted execution",
            )
        }
        if (context.dispatcher.pendingAction?.id != proposal.agentActionId) {
            return reject(
                BrowserExecutionFailureCode.ACTION_ID_MISMATCH,
                "Dispatcher action identity does not match the proposal",
            )
        }

        mark(BrowserExecutionState.VALIDATING_PAGE)
        if (context.page.url != proposal.pageUrl) {
            return reject(
                BrowserExecutionFailureCode.PAGE_URL_MISMATCH,
                "Current page does not match the proposed page",
            )
        }
        if (context.page.capturedAtEpochMs != proposal.pageCapturedAtEpochMs) {
            return reject(
                BrowserExecutionFailureCode.PAGE_SNAPSHOT_MISMATCH,
                "Current page snapshot differs from the proposed snapshot",
            )
        }
        val pageAgeMs = context.nowEpochMs - proposal.pageCapturedAtEpochMs
        if (pageAgeMs < 0 || pageAgeMs > proposal.maximumPageAgeMs) {
            return reject(
                BrowserExecutionFailureCode.PAGE_CONTEXT_STALE,
                "Page context is outside the admitted freshness window",
            )
        }

        mark(BrowserExecutionState.VALIDATING_CONFIRMATION)
        val confirmation = context.confirmation ?: return reject(
            BrowserExecutionFailureCode.CONFIRMATION_REQUIRED,
            "Explicit confirmation is required before browser execution",
        )
        if (
            confirmation.proposalId != proposal.id ||
            confirmation.agentActionId != proposal.agentActionId ||
            confirmation.pageUrl != proposal.pageUrl ||
            confirmation.targetFingerprint != proposal.targetFingerprint ||
            confirmation.confirmedAtEpochMs < proposal.createdAtEpochMs ||
            confirmation.confirmedAtEpochMs > context.nowEpochMs
        ) {
            return reject(
                BrowserExecutionFailureCode.CONFIRMATION_MISMATCH,
                "Confirmation receipt does not match the exact proposal subject",
            )
        }
        if (context.nowEpochMs - confirmation.confirmedAtEpochMs > proposal.maximumConfirmationAgeMs) {
            return reject(
                BrowserExecutionFailureCode.CONFIRMATION_STALE,
                "Confirmation receipt is outside the admitted freshness window",
            )
        }

        return try {
            withTimeout(proposal.timeoutMs) {
                mark(BrowserExecutionState.RESOLVING_TARGET)
                val resolved = platform.resolve(
                    BrowserTargetQuery(
                        pageUrl = proposal.pageUrl,
                        fingerprint = proposal.targetFingerprint,
                        expectedRole = proposal.expectedRole,
                        expectedAccessibleName = proposal.expectedAccessibleName,
                    ),
                )

                if (context.userInteraction.isUserInteracting()) {
                    mark(BrowserExecutionState.CANCELLED)
                    return@withTimeout BrowserActionExecutionResult.Cancelled(
                        proposalId = proposal.id,
                        trace = trace.toList(),
                    )
                }

                val target = when (resolved.size) {
                    0 -> return@withTimeout reject(
                        BrowserExecutionFailureCode.TARGET_NOT_FOUND,
                        "Target fingerprint did not resolve",
                    )
                    1 -> resolved.single()
                    else -> return@withTimeout reject(
                        BrowserExecutionFailureCode.TARGET_AMBIGUOUS,
                        "Target fingerprint resolved to multiple elements",
                    )
                }

                mark(BrowserExecutionState.REVALIDATING_TARGET)
                validateTarget(proposal, target)?.let { failure ->
                    return@withTimeout reject(failure.first, failure.second)
                }

                if (context.userInteraction.isUserInteracting()) {
                    mark(BrowserExecutionState.CANCELLED)
                    return@withTimeout BrowserActionExecutionResult.Cancelled(
                        proposalId = proposal.id,
                        trace = trace.toList(),
                    )
                }

                mark(BrowserExecutionState.EXECUTING)
                val cancellationSignal = BrowserActionCancellationSignal {
                    context.userInteraction.isUserInteracting()
                }
                when (
                    val platformResult = platform.perform(
                        BrowserActionCommand(
                            proposalId = proposal.id,
                            pageUrl = proposal.pageUrl,
                            targetExecutionToken = target.executionToken,
                            targetFingerprint = target.fingerprint,
                            kind = proposal.kind,
                            payload = proposal.payload,
                        ),
                        cancellationSignal,
                    )
                ) {
                    PlatformBrowserActionResult.Completed -> {
                        mark(BrowserExecutionState.SUCCEEDED)
                        BrowserActionExecutionResult.Succeeded(
                            proposalId = proposal.id,
                            trace = trace.toList(),
                        )
                    }
                    PlatformBrowserActionResult.CancelledBeforeSideEffect -> {
                        mark(BrowserExecutionState.CANCELLED)
                        BrowserActionExecutionResult.Cancelled(
                            proposalId = proposal.id,
                            trace = trace.toList(),
                        )
                    }
                    is PlatformBrowserActionResult.Rejected -> reject(
                        BrowserExecutionFailureCode.PLATFORM_REJECTED,
                        "Platform rejected the typed browser command: ${platformResult.code}",
                    )
                    is PlatformBrowserActionResult.Failed -> {
                        mark(BrowserExecutionState.FAILED)
                        BrowserActionExecutionResult.Failed(
                            proposalId = proposal.id,
                            code = BrowserExecutionFailureCode.PLATFORM_FAILED,
                            message = "Platform browser execution failed: ${platformResult.code}",
                            retryable = platformResult.retryable,
                            sideEffectState = platformResult.sideEffectState,
                            trace = trace.toList(),
                        )
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            mark(BrowserExecutionState.TIMED_OUT)
            BrowserActionExecutionResult.TimedOut(
                proposalId = proposal.id,
                sideEffectState = if (trace.any { it.state == BrowserExecutionState.EXECUTING }) {
                    BrowserSideEffectState.UNKNOWN
                } else {
                    BrowserSideEffectState.NONE
                },
                trace = trace.toList(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun validateTarget(
        proposal: BrowserActionProposal,
        target: ResolvedBrowserTarget,
    ): Pair<BrowserExecutionFailureCode, String>? {
        if (target.pageUrl != proposal.pageUrl) {
            return BrowserExecutionFailureCode.TARGET_PAGE_MISMATCH to
                "Resolved target belongs to another page"
        }
        if (target.fingerprint != proposal.targetFingerprint) {
            return BrowserExecutionFailureCode.TARGET_FINGERPRINT_MISMATCH to
                "Resolved target fingerprint changed"
        }
        if (
            proposal.expectedRole != null &&
            !proposal.expectedRole.equals(target.role, ignoreCase = true)
        ) {
            return BrowserExecutionFailureCode.TARGET_ROLE_MISMATCH to
                "Resolved target role changed"
        }
        if (
            proposal.expectedAccessibleName != null &&
            proposal.expectedAccessibleName.trim() != target.accessibleName.trim()
        ) {
            return BrowserExecutionFailureCode.TARGET_NAME_MISMATCH to
                "Resolved target accessible name changed"
        }
        if (!target.visible) {
            return BrowserExecutionFailureCode.TARGET_HIDDEN to "Resolved target is not visible"
        }
        if (!target.enabled) {
            return BrowserExecutionFailureCode.TARGET_DISABLED to "Resolved target is disabled"
        }
        if (
            proposal.kind in setOf(BrowserActionKind.FILL_TEXT, BrowserActionKind.SELECT_OPTION) &&
            !target.editable
        ) {
            return BrowserExecutionFailureCode.TARGET_NOT_EDITABLE to
                "Resolved target is not editable"
        }
        if (target.isSensitive()) {
            return BrowserExecutionFailureCode.SENSITIVE_TARGET to
                "Sensitive, payment, password, secret, or cross-origin targets are not executable"
        }
        return null
    }

    private fun ResolvedBrowserTarget.isSensitive(): Boolean {
        if (sensitivity != BrowserTargetSensitivity.NONE) return true
        return inputType?.lowercase() in SENSITIVE_INPUT_TYPES
    }

    private companion object {
        val SENSITIVE_INPUT_TYPES = setOf(
            "password",
            "cc-number",
            "cc-csc",
            "cc-exp",
            "credit-card",
            "payment",
            "secret",
            "token",
        )
    }
}
