package dev.ed3c.autowebview.executor

import dev.ed3c.autowebview.dispatcher.DispatcherMode
import dev.ed3c.autowebview.dispatcher.DispatcherSnapshot
import dev.ed3c.autowebview.domain.ActionRisk
import dev.ed3c.autowebview.domain.PageContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BrowserActionKind {
    CLICK,
    FILL_TEXT,
    SELECT_OPTION,
}

@Serializable
sealed interface BrowserActionPayload

@Serializable
@SerialName("click")
data object ClickPayload : BrowserActionPayload

@Serializable
@SerialName("fill_text")
data class FillTextPayload(
    val value: String,
) : BrowserActionPayload {
    init {
        require(value.length <= MAX_VALUE_LENGTH) { "Fill value exceeds the bounded payload size" }
        require(value.none(Char::isISOControl) || value.all { it == '\n' || it == '\t' || !it.isISOControl() }) {
            "Fill value contains unsupported control characters"
        }
    }

    private companion object {
        const val MAX_VALUE_LENGTH = 2_048
    }
}

@Serializable
@SerialName("select_option")
data class SelectOptionPayload(
    val value: String,
) : BrowserActionPayload {
    init {
        require(value.isNotBlank()) { "Option value cannot be blank" }
        require(value.length <= MAX_VALUE_LENGTH) { "Option value exceeds the bounded payload size" }
        require(value.none(Char::isISOControl)) { "Option value contains control characters" }
    }

    private companion object {
        const val MAX_VALUE_LENGTH = 512
    }
}

@Serializable
data class BrowserActionProposal(
    val id: String,
    val agentActionId: String,
    val pageUrl: String,
    val pageCapturedAtEpochMs: Long,
    val targetFingerprint: String,
    val expectedRole: String? = null,
    val expectedAccessibleName: String? = null,
    val kind: BrowserActionKind,
    val payload: BrowserActionPayload,
    val risk: ActionRisk = ActionRisk.HIGH,
    val createdAtEpochMs: Long,
    val maximumPageAgeMs: Long = 15_000,
    val maximumConfirmationAgeMs: Long = 30_000,
    val timeoutMs: Long = 5_000,
) {
    init {
        require(id.isNotBlank()) { "Proposal id cannot be blank" }
        require(agentActionId.isNotBlank()) { "Agent action id cannot be blank" }
        require(pageUrl.isNotBlank()) { "Page URL cannot be blank" }
        require(targetFingerprint.isNotBlank()) { "Target fingerprint cannot be blank" }
        require(pageCapturedAtEpochMs >= 0) { "Page capture time cannot be negative" }
        require(createdAtEpochMs >= 0) { "Proposal time cannot be negative" }
        require(maximumPageAgeMs >= 0) { "Maximum page age cannot be negative" }
        require(maximumConfirmationAgeMs >= 0) { "Maximum confirmation age cannot be negative" }
        require(timeoutMs > 0) { "Timeout must be positive" }
        require(payload.kind() == kind) { "Payload does not match the action kind" }
        require(risk >= ActionRisk.MEDIUM) {
            "State-changing browser actions require at least MEDIUM risk"
        }
    }
}

@Serializable
data class BrowserActionConfirmationReceipt(
    val proposalId: String,
    val agentActionId: String,
    val pageUrl: String,
    val targetFingerprint: String,
    val confirmedAtEpochMs: Long,
) {
    init {
        require(proposalId.isNotBlank()) { "Proposal id cannot be blank" }
        require(agentActionId.isNotBlank()) { "Agent action id cannot be blank" }
        require(pageUrl.isNotBlank()) { "Page URL cannot be blank" }
        require(targetFingerprint.isNotBlank()) { "Target fingerprint cannot be blank" }
        require(confirmedAtEpochMs >= 0) { "Confirmation time cannot be negative" }
    }
}

@Serializable
enum class BrowserTargetSensitivity {
    NONE,
    PASSWORD,
    PAYMENT,
    SECRET,
    CROSS_ORIGIN,
}

@Serializable
data class BrowserTargetQuery(
    val pageUrl: String,
    val fingerprint: String,
    val expectedRole: String? = null,
    val expectedAccessibleName: String? = null,
)

@Serializable
data class ResolvedBrowserTarget(
    val executionToken: String,
    val pageUrl: String,
    val fingerprint: String,
    val role: String? = null,
    val accessibleName: String = "",
    val tag: String = "",
    val inputType: String? = null,
    val visible: Boolean,
    val enabled: Boolean,
    val editable: Boolean,
    val sensitivity: BrowserTargetSensitivity = BrowserTargetSensitivity.NONE,
) {
    init {
        require(executionToken.isNotBlank()) { "Execution token cannot be blank" }
        require(pageUrl.isNotBlank()) { "Resolved page URL cannot be blank" }
        require(fingerprint.isNotBlank()) { "Resolved fingerprint cannot be blank" }
    }
}

@Serializable
data class BrowserActionCommand(
    val proposalId: String,
    val pageUrl: String,
    val targetExecutionToken: String,
    val targetFingerprint: String,
    val kind: BrowserActionKind,
    val payload: BrowserActionPayload,
)

fun interface BrowserActionCancellationSignal {
    fun isCancellationRequested(): Boolean
}

interface BrowserActionPlatform {
    suspend fun resolve(query: BrowserTargetQuery): List<ResolvedBrowserTarget>

    suspend fun perform(
        command: BrowserActionCommand,
        cancellationSignal: BrowserActionCancellationSignal,
    ): PlatformBrowserActionResult
}

@Serializable
sealed interface PlatformBrowserActionResult {
    @Serializable
    @SerialName("completed")
    data object Completed : PlatformBrowserActionResult

    @Serializable
    @SerialName("cancelled_before_side_effect")
    data object CancelledBeforeSideEffect : PlatformBrowserActionResult

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val code: String,
        val message: String,
    ) : PlatformBrowserActionResult

    @Serializable
    @SerialName("failed")
    data class Failed(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
        val sideEffectState: BrowserSideEffectState = BrowserSideEffectState.UNKNOWN,
    ) : PlatformBrowserActionResult
}

@Serializable
enum class BrowserSideEffectState {
    NONE,
    APPLIED,
    UNKNOWN,
}

fun interface UserInteractionProbe {
    fun isUserInteracting(): Boolean
}

data class BrowserActionExecutionContext(
    val page: PageContext,
    val dispatcher: DispatcherSnapshot,
    val confirmation: BrowserActionConfirmationReceipt?,
    val nowEpochMs: Long,
    val userInteraction: UserInteractionProbe = UserInteractionProbe { false },
)

@Serializable
enum class BrowserExecutionState {
    VALIDATING_AUTHORITY,
    VALIDATING_PAGE,
    VALIDATING_CONFIRMATION,
    RESOLVING_TARGET,
    REVALIDATING_TARGET,
    EXECUTING,
    SUCCEEDED,
    REJECTED,
    CANCELLED,
    TIMED_OUT,
    FAILED,
}

@Serializable
enum class BrowserExecutionFailureCode {
    USER_PREEMPTED,
    DISPATCHER_NOT_EXECUTING,
    ACTION_ID_MISMATCH,
    PAGE_URL_MISMATCH,
    PAGE_SNAPSHOT_MISMATCH,
    PAGE_CONTEXT_STALE,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_MISMATCH,
    CONFIRMATION_STALE,
    TARGET_NOT_FOUND,
    TARGET_AMBIGUOUS,
    TARGET_PAGE_MISMATCH,
    TARGET_FINGERPRINT_MISMATCH,
    TARGET_ROLE_MISMATCH,
    TARGET_NAME_MISMATCH,
    TARGET_HIDDEN,
    TARGET_DISABLED,
    TARGET_NOT_EDITABLE,
    SENSITIVE_TARGET,
    PLATFORM_REJECTED,
    PLATFORM_FAILED,
    TIMEOUT,
}

@Serializable
data class BrowserExecutionTraceEntry(
    val state: BrowserExecutionState,
    val atEpochMs: Long,
)

@Serializable
sealed interface BrowserActionExecutionResult {
    val proposalId: String
    val trace: List<BrowserExecutionTraceEntry>

    @Serializable
    @SerialName("succeeded")
    data class Succeeded(
        override val proposalId: String,
        override val trace: List<BrowserExecutionTraceEntry>,
    ) : BrowserActionExecutionResult

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        override val proposalId: String,
        val code: BrowserExecutionFailureCode,
        val message: String,
        override val trace: List<BrowserExecutionTraceEntry>,
    ) : BrowserActionExecutionResult

    @Serializable
    @SerialName("cancelled")
    data class Cancelled(
        override val proposalId: String,
        val code: BrowserExecutionFailureCode = BrowserExecutionFailureCode.USER_PREEMPTED,
        override val trace: List<BrowserExecutionTraceEntry>,
    ) : BrowserActionExecutionResult

    @Serializable
    @SerialName("timed_out")
    data class TimedOut(
        override val proposalId: String,
        val sideEffectState: BrowserSideEffectState = BrowserSideEffectState.UNKNOWN,
        override val trace: List<BrowserExecutionTraceEntry>,
    ) : BrowserActionExecutionResult

    @Serializable
    @SerialName("failed")
    data class Failed(
        override val proposalId: String,
        val code: BrowserExecutionFailureCode,
        val message: String,
        val retryable: Boolean,
        val sideEffectState: BrowserSideEffectState,
        override val trace: List<BrowserExecutionTraceEntry>,
    ) : BrowserActionExecutionResult
}

internal fun BrowserActionPayload.kind(): BrowserActionKind = when (this) {
    ClickPayload -> BrowserActionKind.CLICK
    is FillTextPayload -> BrowserActionKind.FILL_TEXT
    is SelectOptionPayload -> BrowserActionKind.SELECT_OPTION
}

internal fun DispatcherSnapshot.admits(proposal: BrowserActionProposal): Boolean =
    mode == DispatcherMode.EXECUTING && pendingAction?.id == proposal.agentActionId
