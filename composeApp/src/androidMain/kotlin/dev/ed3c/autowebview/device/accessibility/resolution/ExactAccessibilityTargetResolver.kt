package dev.ed3c.autowebview.device.accessibility.resolution

import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.contract.DeviceUiElementSnapshot
import dev.ed3c.autowebview.device.contract.DeviceUiSnapshot

data class ExactAccessibilityTargetRequest(
    val target: DeviceTargetRef.UiTarget,
    val packageName: String,
    val windowId: String,
    val displayId: String,
    val taskId: String,
    val expectedRole: String? = null,
    val expectedAccessibleName: String? = null,
    val nowEpochMs: Long,
    val maximumSnapshotAgeMs: Long,
    val tokenTtlMs: Long = 2_000,
)

enum class TargetStaleReason {
    SUBJECT_MISMATCH,
    TASK_MISMATCH,
    GENERATION_MISMATCH,
    SNAPSHOT_EXPIRED,
}

sealed interface ExactAccessibilityTargetResolution {
    data class Resolved(
        val executionToken: String,
        val fingerprint: String,
        val snapshotVersion: Long,
        val expiresAtEpochMs: Long,
    ) : ExactAccessibilityTargetResolution

    data object NotFound : ExactAccessibilityTargetResolution
    data object Ambiguous : ExactAccessibilityTargetResolution
    data class Stale(val reason: TargetStaleReason) : ExactAccessibilityTargetResolution
}

fun interface OpaqueAccessibilityTargetTokenFactory {
    fun create(snapshot: DeviceUiSnapshot, element: DeviceUiElementSnapshot): String
}

class ExactAccessibilityTargetResolver(
    private val tokenFactory: OpaqueAccessibilityTargetTokenFactory,
) {
    fun resolve(
        snapshot: DeviceUiSnapshot,
        request: ExactAccessibilityTargetRequest,
    ): ExactAccessibilityTargetResolution {
        val subject = snapshot.subject
        if (
            subject.packageName != request.packageName ||
            subject.windowId != request.windowId ||
            subject.displayId != request.displayId
        ) {
            return ExactAccessibilityTargetResolution.Stale(TargetStaleReason.SUBJECT_MISMATCH)
        }
        if (snapshot.taskId != request.taskId) {
            return ExactAccessibilityTargetResolution.Stale(TargetStaleReason.TASK_MISMATCH)
        }
        if (request.target.snapshotVersion != subject.snapshotVersion) {
            return ExactAccessibilityTargetResolution.Stale(TargetStaleReason.GENERATION_MISMATCH)
        }
        if (!snapshot.isFresh(request.nowEpochMs, request.maximumSnapshotAgeMs)) {
            return ExactAccessibilityTargetResolution.Stale(TargetStaleReason.SNAPSHOT_EXPIRED)
        }
        require(request.tokenTtlMs in 1..30_000) { "Target token TTL is outside the bounded range" }

        val candidates = snapshot.exactTargetCandidates(request.target).filter { element ->
            (request.expectedRole == null || element.role == request.expectedRole) &&
                (request.expectedAccessibleName == null || element.accessibleName == request.expectedAccessibleName) &&
                element.visible && element.enabled
        }
        return when (candidates.size) {
            0 -> ExactAccessibilityTargetResolution.NotFound
            1 -> {
                val candidate = candidates.single()
                val token = tokenFactory.create(snapshot, candidate)
                require(token.length in 1..256 && token.none(Char::isWhitespace) && token.none(Char::isISOControl)) {
                    "Target token must be a bounded opaque value"
                }
                ExactAccessibilityTargetResolution.Resolved(
                    executionToken = token,
                    fingerprint = candidate.fingerprint,
                    snapshotVersion = subject.snapshotVersion,
                    expiresAtEpochMs = request.nowEpochMs + request.tokenTtlMs,
                )
            }
            else -> ExactAccessibilityTargetResolution.Ambiguous
        }
    }
}
