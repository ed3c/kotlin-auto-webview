package dev.ed3c.autowebview.device.accessibility.observation

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.ed3c.autowebview.device.privacy.AccessibilityNodeSensitivityClassifier
import dev.ed3c.autowebview.device.privacy.AccessibilityNodeSensitivityMetadata
import dev.ed3c.autowebview.device.privacy.AccessibilitySensitivity

@JvmInline
value class AccessibilityTraversalClock(private val now: () -> Long) {
    fun nowMs(): Long = now()
}

data class AndroidAccessibilityReadRequest(
    val taskId: String,
    val capturedAtEpochMs: Long,
    val privacyPolicyVersion: String,
    val expectedPackageName: String? = null,
)

enum class AndroidAccessibilityAbsentReason {
    SERVICE_DISCONNECTED,
    WINDOW_UNAVAILABLE,
    ROOT_UNAVAILABLE,
}

enum class AndroidAccessibilityReadRejection {
    PACKAGE_MISMATCH,
    NODE_BUDGET_EXCEEDED,
    DEPTH_BUDGET_EXCEEDED,
    TIME_BUDGET_EXCEEDED,
    PROJECTOR_REJECTED,
}

sealed interface AndroidAccessibilityReadResult {
    data class Published(val frame: AccessibilityObservationFrame) : AndroidAccessibilityReadResult
    data class Absent(val reason: AndroidAccessibilityAbsentReason) : AndroidAccessibilityReadResult
    data class Rejected(val reason: AndroidAccessibilityReadRejection) : AndroidAccessibilityReadResult
}

class AndroidAccessibilityTreeReader(
    private val session: AccessibilityObservationSession,
    private val classifier: AccessibilityNodeSensitivityClassifier = AccessibilityNodeSensitivityClassifier(),
    private val maximumNodes: Int = 2_048,
    private val maximumDepth: Int = 64,
    private val maximumTraversalMs: Long = 250,
    private val traversalClock: AccessibilityTraversalClock = AccessibilityTraversalClock { SystemClock.uptimeMillis() },
) {
    init {
        require(maximumNodes in 1..2_048) { "Accessibility node budget is invalid" }
        require(maximumDepth in 1..128) { "Accessibility depth budget is invalid" }
        require(maximumTraversalMs in 1..5_000) { "Accessibility traversal time budget is invalid" }
    }

    fun read(
        window: AccessibilityWindowInfo?,
        request: AndroidAccessibilityReadRequest,
    ): AndroidAccessibilityReadResult {
        val lease = session.captureLease()
            ?: return AndroidAccessibilityReadResult.Absent(AndroidAccessibilityAbsentReason.SERVICE_DISCONNECTED)
        val exactWindow = window
            ?: return AndroidAccessibilityReadResult.Absent(AndroidAccessibilityAbsentReason.WINDOW_UNAVAILABLE)
        val root = exactWindow.root
            ?: return AndroidAccessibilityReadResult.Absent(AndroidAccessibilityAbsentReason.ROOT_UNAVAILABLE)
        val startedAt = traversalClock.nowMs()
        val nodes = mutableListOf<RawAccessibilityNode>()
        return try {
            val packageName = root.packageName?.toString()?.trim().orEmpty()
            if (request.expectedPackageName != null && packageName != request.expectedPackageName) {
                AndroidAccessibilityReadResult.Rejected(AndroidAccessibilityReadRejection.PACKAGE_MISMATCH)
            } else {
                when (visit(root, "node-0", null, 0, startedAt, nodes)) {
                    VisitVerdict.OK -> AndroidAccessibilityReadResult.Published(
                        AccessibilityObservationFrame(
                            packageName = packageName,
                            windowId = "window-${exactWindow.id}",
                            displayId = "display-${displayId(exactWindow)}",
                            taskId = request.taskId,
                            snapshotVersion = lease.generation,
                            capturedAtEpochMs = request.capturedAtEpochMs,
                            eventSequence = lease.eventSequence,
                            privacyPolicyVersion = request.privacyPolicyVersion,
                            nodes = nodes,
                        ),
                    )
                    VisitVerdict.NODE_BUDGET -> AndroidAccessibilityReadResult.Rejected(AndroidAccessibilityReadRejection.NODE_BUDGET_EXCEEDED)
                    VisitVerdict.DEPTH_BUDGET -> AndroidAccessibilityReadResult.Rejected(AndroidAccessibilityReadRejection.DEPTH_BUDGET_EXCEEDED)
                    VisitVerdict.TIME_BUDGET -> AndroidAccessibilityReadResult.Rejected(AndroidAccessibilityReadRejection.TIME_BUDGET_EXCEEDED)
                }
            }
        } finally {
            releaseNode(root)
        }
    }

    private fun visit(
        node: AccessibilityNodeInfo,
        localId: String,
        parentLocalId: String?,
        depth: Int,
        startedAt: Long,
        output: MutableList<RawAccessibilityNode>,
    ): VisitVerdict {
        if (depth > maximumDepth) return VisitVerdict.DEPTH_BUDGET
        if (output.size >= maximumNodes) return VisitVerdict.NODE_BUDGET
        if (traversalClock.nowMs() - startedAt > maximumTraversalMs) return VisitVerdict.TIME_BUDGET

        val metadata = AccessibilityNodeSensitivityMetadata(
            password = node.isPassword,
            editable = node.isEditable,
            className = node.className?.toString(),
            viewIdResourceName = runCatching { node.viewIdResourceName }.getOrNull(),
        )
        val sensitivity = classifier.classify(metadata)
        val accessibleName = if (sensitivity.requiresRedaction()) {
            ""
        } else {
            runCatching {
                (node.contentDescription ?: node.text)?.toString().orEmpty()
            }.getOrDefault("")
        }
        output += RawAccessibilityNode(
            localId = localId,
            parentLocalId = parentLocalId,
            role = role(node.className?.toString()),
            accessibleName = accessibleName,
            visible = node.isVisibleToUser,
            enabled = node.isEnabled,
            editable = node.isEditable,
            sensitivity = sensitivity,
        )

        for (index in 0 until node.childCount) {
            if (traversalClock.nowMs() - startedAt > maximumTraversalMs) return VisitVerdict.TIME_BUDGET
            val child = node.getChild(index) ?: continue
            val verdict = try {
                visit(
                    node = child,
                    localId = "$localId-$index",
                    parentLocalId = localId,
                    depth = depth + 1,
                    startedAt = startedAt,
                    output = output,
                )
            } finally {
                releaseNode(child)
            }
            if (verdict != VisitVerdict.OK) return verdict
        }
        return VisitVerdict.OK
    }

    private fun role(className: String?): String = when {
        className == null -> "node"
        className.contains("EditText", ignoreCase = true) -> "edit-text"
        className.contains("Button", ignoreCase = true) -> "button"
        className.contains("WebView", ignoreCase = true) -> "web-view"
        className.contains("RecyclerView", ignoreCase = true) -> "list"
        className.contains("ListView", ignoreCase = true) -> "list"
        className.contains("CheckBox", ignoreCase = true) -> "checkbox"
        className.contains("Switch", ignoreCase = true) -> "switch"
        else -> "node"
    }

    private fun displayId(window: AccessibilityWindowInfo): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.displayId else 0

    @Suppress("DEPRECATION")
    private fun releaseNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            node.recycle()
        }
    }

    private fun AccessibilitySensitivity.requiresRedaction(): Boolean = this in setOf(
        AccessibilitySensitivity.PASSWORD,
        AccessibilitySensitivity.PAYMENT,
        AccessibilitySensitivity.SECRET,
        AccessibilitySensitivity.OTP,
        AccessibilitySensitivity.PRIVATE_MESSAGE,
        AccessibilitySensitivity.POLICY_DENIED,
    )

    private enum class VisitVerdict {
        OK,
        NODE_BUDGET,
        DEPTH_BUDGET,
        TIME_BUDGET,
    }
}
