package dev.ed3c.autowebview.device.contract

import dev.ed3c.autowebview.device.catalog.DeviceCapabilityId
import dev.ed3c.autowebview.device.policy.DeviceActionRisk
import dev.ed3c.autowebview.device.policy.DistributionProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object DeviceContractSchema {
    const val VERSION = "kotlin-auto-webview/device-action/v1"
}

@Serializable
enum class DeviceActionKind {
    UI_CLICK,
    UI_FILL_TEXT,
    UI_SELECT_OPTION,
    OBSERVE_SANITIZED_UI,
}

@Serializable
enum class DeviceConfirmationClass {
    NONE,
    USER_CONFIRMATION,
    EXTERNAL_ADMISSION,
}

@Serializable
enum class DeviceEffectState {
    NONE,
    APPLIED,
    UNKNOWN,
}

@Serializable
data class DeviceSubjectRef(
    val packageName: String,
    val windowId: String,
    val displayId: String,
    val snapshotVersion: Long,
    val capturedAtEpochMs: Long,
) {
    init {
        DeviceContractValidation.requirePackageName(packageName)
        DeviceContractValidation.requireIdentifier(windowId, "window id")
        DeviceContractValidation.requireIdentifier(displayId, "display id")
        require(snapshotVersion >= 0) { "Snapshot version cannot be negative" }
        require(capturedAtEpochMs >= 0) { "Snapshot capture time cannot be negative" }
    }
}

@Serializable
sealed interface DeviceTargetRef {
    @Serializable
    @SerialName("ui_target")
    data class UiTarget(
        val fingerprint: String,
        val snapshotVersion: Long,
    ) : DeviceTargetRef {
        init {
            DeviceContractValidation.requireOpaqueToken(fingerprint, "target fingerprint")
            require(snapshotVersion >= 0) { "Target snapshot version cannot be negative" }
        }
    }

    @Serializable
    @SerialName("resource_target")
    data class ResourceTarget(
        val resourceType: String,
        val resourceId: String,
    ) : DeviceTargetRef {
        init {
            DeviceContractValidation.requireIdentifier(resourceType, "resource type")
            DeviceContractValidation.requireIdentifier(resourceId, "resource id")
        }
    }
}

@Serializable
sealed interface DeviceActionPayload {
    val kind: DeviceActionKind

    @Serializable
    @SerialName("ui_click")
    data object UiClick : DeviceActionPayload {
        override val kind: DeviceActionKind = DeviceActionKind.UI_CLICK
    }

    @Serializable
    @SerialName("ui_fill_text")
    data class UiFillText(
        val value: String,
    ) : DeviceActionPayload {
        override val kind: DeviceActionKind = DeviceActionKind.UI_FILL_TEXT

        init {
            DeviceContractValidation.requireBoundedText(value, "fill text", allowNewline = true, maxLength = 2_048)
        }
    }

    @Serializable
    @SerialName("ui_select_option")
    data class UiSelectOption(
        val value: String,
    ) : DeviceActionPayload {
        override val kind: DeviceActionKind = DeviceActionKind.UI_SELECT_OPTION

        init {
            require(value.isNotBlank()) { "Option value cannot be blank" }
            DeviceContractValidation.requireBoundedText(value, "option value", allowNewline = false, maxLength = 512)
        }
    }

    @Serializable
    @SerialName("observe_sanitized_ui")
    data object ObserveSanitizedUi : DeviceActionPayload {
        override val kind: DeviceActionKind = DeviceActionKind.OBSERVE_SANITIZED_UI
    }
}

@Serializable
data class DeviceActionProposal(
    val schemaVersion: String = DeviceContractSchema.VERSION,
    val proposalId: String,
    val intentId: String,
    val canonicalActionId: String,
    val capabilityId: DeviceCapabilityId,
    val profile: DistributionProfile,
    val subject: DeviceSubjectRef,
    val target: DeviceTargetRef,
    val kind: DeviceActionKind,
    val payload: DeviceActionPayload,
    val payloadDigestSha256: String,
    val risk: DeviceActionRisk,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val maximumSnapshotAgeMs: Long = 15_000,
    val requiredPermissions: Set<String> = emptySet(),
    val confirmationClass: DeviceConfirmationClass,
    val verifierId: String,
    val auditCategory: String,
    val policyVersion: String,
) {
    init {
        require(schemaVersion == DeviceContractSchema.VERSION) { "Unknown device contract schema" }
        DeviceContractValidation.requireIdentifier(proposalId, "proposal id")
        DeviceContractValidation.requireIdentifier(intentId, "intent id")
        DeviceContractValidation.requireIdentifier(canonicalActionId, "canonical action id")
        require(payload.kind == kind) { "Payload kind does not match canonical action kind" }
        DeviceContractValidation.requireSha256(payloadDigestSha256, "payload digest")
        require(createdAtEpochMs >= 0) { "Proposal creation time cannot be negative" }
        require(expiresAtEpochMs >= createdAtEpochMs) { "Proposal expiry precedes creation" }
        require(subject.capturedAtEpochMs <= createdAtEpochMs) { "Snapshot cannot be captured after proposal creation" }
        require(maximumSnapshotAgeMs >= 0) { "Maximum snapshot age cannot be negative" }
        require(requiredPermissions.size <= DeviceContractValidation.MAX_LIST_ITEMS) { "Too many required permissions" }
        requiredPermissions.forEach { DeviceContractValidation.requireIdentifier(it, "permission id") }
        DeviceContractValidation.requireIdentifier(verifierId, "verifier id")
        DeviceContractValidation.requireIdentifier(auditCategory, "audit category")
        DeviceContractValidation.requireIdentifier(policyVersion, "policy version")
        when (kind) {
            DeviceActionKind.UI_CLICK,
            DeviceActionKind.UI_FILL_TEXT,
            DeviceActionKind.UI_SELECT_OPTION,
            -> require(target is DeviceTargetRef.UiTarget) { "UI actions require an opaque UI target" }

            DeviceActionKind.OBSERVE_SANITIZED_UI ->
                require(target is DeviceTargetRef.ResourceTarget) { "Observation requires a typed resource target" }
        }
        if (target is DeviceTargetRef.UiTarget) {
            require(target.snapshotVersion == subject.snapshotVersion) { "Target and subject snapshot versions differ" }
        }
    }
}

@Serializable
data class DeviceConfirmationReceipt(
    val schemaVersion: String = DeviceContractSchema.VERSION,
    val receiptId: String,
    val proposalId: String,
    val canonicalActionId: String,
    val capabilityId: DeviceCapabilityId,
    val profile: DistributionProfile,
    val subject: DeviceSubjectRef,
    val target: DeviceTargetRef,
    val payloadDigestSha256: String,
    val policyVersion: String,
    val confirmedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    init {
        require(schemaVersion == DeviceContractSchema.VERSION) { "Unknown device contract schema" }
        DeviceContractValidation.requireIdentifier(receiptId, "confirmation receipt id")
        DeviceContractValidation.requireIdentifier(proposalId, "proposal id")
        DeviceContractValidation.requireIdentifier(canonicalActionId, "canonical action id")
        DeviceContractValidation.requireSha256(payloadDigestSha256, "payload digest")
        DeviceContractValidation.requireIdentifier(policyVersion, "policy version")
        require(confirmedAtEpochMs >= 0) { "Confirmation time cannot be negative" }
        require(expiresAtEpochMs >= confirmedAtEpochMs) { "Confirmation expiry precedes confirmation" }
    }

    fun matches(proposal: DeviceActionProposal, nowEpochMs: Long): Boolean =
        nowEpochMs >= confirmedAtEpochMs &&
            nowEpochMs <= expiresAtEpochMs &&
            proposalId == proposal.proposalId &&
            canonicalActionId == proposal.canonicalActionId &&
            capabilityId == proposal.capabilityId &&
            profile == proposal.profile &&
            subject == proposal.subject &&
            target == proposal.target &&
            payloadDigestSha256 == proposal.payloadDigestSha256 &&
            policyVersion == proposal.policyVersion
}

@Serializable
data class DeviceActionCommand(
    val schemaVersion: String = DeviceContractSchema.VERSION,
    val proposalId: String,
    val canonicalActionId: String,
    val capabilityId: DeviceCapabilityId,
    val profile: DistributionProfile,
    val subject: DeviceSubjectRef,
    val target: DeviceTargetRef,
    val resolvedTargetToken: String,
    val kind: DeviceActionKind,
    val payload: DeviceActionPayload,
    val payloadDigestSha256: String,
    val verifierId: String,
    val policyVersion: String,
    val confirmationReceiptId: String? = null,
) {
    init {
        require(schemaVersion == DeviceContractSchema.VERSION) { "Unknown device contract schema" }
        DeviceContractValidation.requireIdentifier(proposalId, "proposal id")
        DeviceContractValidation.requireIdentifier(canonicalActionId, "canonical action id")
        DeviceContractValidation.requireOpaqueToken(resolvedTargetToken, "resolved target token")
        require(payload.kind == kind) { "Command payload kind mismatch" }
        DeviceContractValidation.requireSha256(payloadDigestSha256, "payload digest")
        DeviceContractValidation.requireIdentifier(verifierId, "verifier id")
        DeviceContractValidation.requireIdentifier(policyVersion, "policy version")
        confirmationReceiptId?.let { DeviceContractValidation.requireIdentifier(it, "confirmation receipt id") }
    }
}

@Serializable
enum class DeviceVerifierOutcome {
    APPLIED,
    NO_EFFECT,
    INCONCLUSIVE,
}

@Serializable
data class DeviceVerificationEvidence(
    val verifierId: String,
    val subject: DeviceSubjectRef,
    val target: DeviceTargetRef,
    val observedAtEpochMs: Long,
    val outcome: DeviceVerifierOutcome,
    val evidenceDigestSha256: String,
) {
    init {
        DeviceContractValidation.requireIdentifier(verifierId, "verifier id")
        require(observedAtEpochMs >= 0) { "Evidence time cannot be negative" }
        DeviceContractValidation.requireSha256(evidenceDigestSha256, "evidence digest")
    }

    val effectState: DeviceEffectState
        get() = when (outcome) {
            DeviceVerifierOutcome.APPLIED -> DeviceEffectState.APPLIED
            DeviceVerifierOutcome.NO_EFFECT -> DeviceEffectState.NONE
            DeviceVerifierOutcome.INCONCLUSIVE -> DeviceEffectState.UNKNOWN
        }
}

@Serializable
sealed interface DeviceActionResult {
    val proposalId: String

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        override val proposalId: String,
        val code: String,
        val message: String,
    ) : DeviceActionResult {
        init {
            DeviceContractValidation.requireIdentifier(proposalId, "proposal id")
            DeviceContractValidation.requireIdentifier(code, "rejection code")
            DeviceContractValidation.requireBoundedText(message, "rejection message", allowNewline = false, maxLength = 512)
        }
    }

    @Serializable
    @SerialName("cancelled_before_effect")
    data class CancelledBeforeEffect(
        override val proposalId: String,
    ) : DeviceActionResult {
        init { DeviceContractValidation.requireIdentifier(proposalId, "proposal id") }
    }

    @Serializable
    @SerialName("user_action_required")
    data class UserActionRequired(
        override val proposalId: String,
        val confirmationClass: DeviceConfirmationClass,
    ) : DeviceActionResult {
        init { DeviceContractValidation.requireIdentifier(proposalId, "proposal id") }
    }

    @Serializable
    @SerialName("dispatched_awaiting_verification")
    data class DispatchedAwaitingVerification(
        override val proposalId: String,
        val dispatchId: String,
    ) : DeviceActionResult {
        init {
            DeviceContractValidation.requireIdentifier(proposalId, "proposal id")
            DeviceContractValidation.requireOpaqueToken(dispatchId, "dispatch id")
        }
    }

    @Serializable
    @SerialName("verified_applied")
    data class VerifiedApplied(
        override val proposalId: String,
        val evidence: DeviceVerificationEvidence,
    ) : DeviceActionResult {
        init {
            DeviceContractValidation.requireIdentifier(proposalId, "proposal id")
            require(evidence.outcome == DeviceVerifierOutcome.APPLIED) { "Applied result requires APPLIED evidence" }
        }
    }

    @Serializable
    @SerialName("verified_no_effect")
    data class VerifiedNoEffect(
        override val proposalId: String,
        val evidence: DeviceVerificationEvidence,
    ) : DeviceActionResult {
        init {
            DeviceContractValidation.requireIdentifier(proposalId, "proposal id")
            require(evidence.outcome == DeviceVerifierOutcome.NO_EFFECT) { "No-effect result requires NO_EFFECT evidence" }
        }
    }

    @Serializable
    @SerialName("failed_known_effect")
    data class FailedKnownEffect(
        override val proposalId: String,
        val code: String,
        val effectState: DeviceEffectState,
        val evidence: DeviceVerificationEvidence,
    ) : DeviceActionResult {
        init {
            DeviceContractValidation.requireIdentifier(proposalId, "proposal id")
            DeviceContractValidation.requireIdentifier(code, "failure code")
            require(effectState != DeviceEffectState.UNKNOWN) { "Known-effect failure cannot be UNKNOWN" }
            require(effectState == evidence.effectState) { "Failure effect state disagrees with verifier evidence" }
        }
    }

    @Serializable
    @SerialName("failed_unknown_effect")
    data class FailedUnknownEffect(
        override val proposalId: String,
        val code: String,
        val evidence: DeviceVerificationEvidence? = null,
    ) : DeviceActionResult {
        init {
            DeviceContractValidation.requireIdentifier(proposalId, "proposal id")
            DeviceContractValidation.requireIdentifier(code, "failure code")
            require(evidence == null || evidence.outcome == DeviceVerifierOutcome.INCONCLUSIVE) {
                "Unknown-effect failure accepts only inconclusive evidence"
            }
        }
    }

    @Serializable
    @SerialName("timed_out")
    data class TimedOut(
        override val proposalId: String,
        val dispatched: Boolean,
        val effectState: DeviceEffectState,
        val evidence: DeviceVerificationEvidence? = null,
    ) : DeviceActionResult {
        init {
            DeviceContractValidation.requireIdentifier(proposalId, "proposal id")
            if (!dispatched) {
                require(effectState == DeviceEffectState.NONE) { "Pre-dispatch timeout must have NONE effect" }
                require(evidence == null) { "Pre-dispatch timeout cannot claim postcondition evidence" }
            } else if (effectState == DeviceEffectState.NONE) {
                require(evidence?.outcome == DeviceVerifierOutcome.NO_EFFECT) {
                    "Post-dispatch NONE requires explicit NO_EFFECT verifier evidence"
                }
            } else if (effectState == DeviceEffectState.APPLIED) {
                require(evidence?.outcome == DeviceVerifierOutcome.APPLIED) {
                    "Post-dispatch APPLIED requires explicit APPLIED verifier evidence"
                }
            } else {
                require(evidence == null || evidence.outcome == DeviceVerifierOutcome.INCONCLUSIVE) {
                    "UNKNOWN timeout accepts only inconclusive evidence"
                }
            }
        }
    }
}

internal object DeviceContractValidation {
    const val MAX_LIST_ITEMS = 32
    private const val MAX_IDENTIFIER_LENGTH = 128
    private const val MAX_TOKEN_LENGTH = 256
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val sha256 = Regex("[0-9a-f]{64}")

    fun requireIdentifier(value: String, field: String) {
        require(value.length in 1..MAX_IDENTIFIER_LENGTH && identifier.matches(value)) {
            "$field must be a bounded canonical identifier"
        }
    }

    fun requireOpaqueToken(value: String, field: String) {
        require(value.length in 1..MAX_TOKEN_LENGTH) { "$field is outside the bounded token size" }
        require(value.none(Char::isISOControl)) { "$field contains control characters" }
        require(value.none { it.isWhitespace() }) { "$field cannot contain whitespace" }
        require(value.none { it in "*?;&|`$<>\\\"'" }) { "$field contains command, wildcard, or selector metacharacters" }
    }

    fun requirePackageName(value: String) {
        require(value.length <= 255 && packageName.matches(value)) {
            "Package subject must be an exact package name without wildcard authority"
        }
    }

    fun requireSha256(value: String, field: String) {
        require(sha256.matches(value)) { "$field must be a lowercase SHA-256 digest" }
    }

    fun requireBoundedText(value: String, field: String, allowNewline: Boolean, maxLength: Int) {
        require(value.length <= maxLength) { "$field exceeds bounded size" }
        require(value.none { char ->
            char.isISOControl() && !(allowNewline && (char == '\n' || char == '\t'))
        }) { "$field contains unsupported control characters" }
    }
}
