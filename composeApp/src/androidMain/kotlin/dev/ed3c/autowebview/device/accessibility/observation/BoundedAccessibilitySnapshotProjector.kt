package dev.ed3c.autowebview.device.accessibility.observation

import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceUiElementSnapshot
import dev.ed3c.autowebview.device.contract.DeviceUiSnapshot
import dev.ed3c.autowebview.device.privacy.AccessibilityPrivacyPolicy
import dev.ed3c.autowebview.device.privacy.AccessibilitySensitivity
import java.security.MessageDigest

data class RawAccessibilityNode(
    val localId: String,
    val parentLocalId: String? = null,
    val role: String? = null,
    val accessibleName: String = "",
    val visible: Boolean,
    val enabled: Boolean,
    val editable: Boolean,
    val sensitivity: AccessibilitySensitivity,
)

data class AccessibilityObservationFrame(
    val packageName: String,
    val windowId: String,
    val displayId: String,
    val taskId: String,
    val snapshotVersion: Long,
    val capturedAtEpochMs: Long,
    val eventSequence: Long,
    val privacyPolicyVersion: String,
    val nodes: List<RawAccessibilityNode>,
)

enum class AccessibilityCaptureRejection {
    INVALID_SUBJECT,
    NODE_BUDGET_EXCEEDED,
    DUPLICATE_NODE_ID,
    DANGLING_PARENT,
    DEPTH_BUDGET_EXCEEDED,
    CYCLIC_PARENTAGE,
    INVALID_NODE,
}

sealed interface AccessibilityCaptureResult {
    data class Published(val snapshot: DeviceUiSnapshot) : AccessibilityCaptureResult
    data class Rejected(val code: AccessibilityCaptureRejection) : AccessibilityCaptureResult
}

class BoundedAccessibilitySnapshotProjector(
    private val privacyPolicy: AccessibilityPrivacyPolicy = AccessibilityPrivacyPolicy(),
    private val maximumNodes: Int = 2_048,
    private val maximumDepth: Int = 64,
) {
    init {
        require(maximumNodes in 1..2_048) { "Accessibility node budget is invalid" }
        require(maximumDepth in 1..128) { "Accessibility depth budget is invalid" }
    }

    fun project(frame: AccessibilityObservationFrame): AccessibilityCaptureResult {
        if (frame.nodes.size > maximumNodes) {
            return AccessibilityCaptureResult.Rejected(AccessibilityCaptureRejection.NODE_BUDGET_EXCEEDED)
        }
        val nodesById = frame.nodes.associateBy(RawAccessibilityNode::localId)
        if (nodesById.size != frame.nodes.size) {
            return AccessibilityCaptureResult.Rejected(AccessibilityCaptureRejection.DUPLICATE_NODE_ID)
        }
        if (frame.nodes.any { it.localId.isBlank() || it.localId.length > 128 || it.localId.any(Char::isISOControl) }) {
            return AccessibilityCaptureResult.Rejected(AccessibilityCaptureRejection.INVALID_NODE)
        }
        if (frame.nodes.any { it.parentLocalId != null && it.parentLocalId !in nodesById }) {
            return AccessibilityCaptureResult.Rejected(AccessibilityCaptureRejection.DANGLING_PARENT)
        }
        when (validateDepth(nodesById)) {
            DepthVerdict.OK -> Unit
            DepthVerdict.TOO_DEEP -> return AccessibilityCaptureResult.Rejected(AccessibilityCaptureRejection.DEPTH_BUDGET_EXCEEDED)
            DepthVerdict.CYCLE -> return AccessibilityCaptureResult.Rejected(AccessibilityCaptureRejection.CYCLIC_PARENTAGE)
        }

        val subject = try {
            DeviceSubjectRef(
                packageName = frame.packageName,
                windowId = frame.windowId,
                displayId = frame.displayId,
                snapshotVersion = frame.snapshotVersion,
                capturedAtEpochMs = frame.capturedAtEpochMs,
            )
        } catch (_: IllegalArgumentException) {
            return AccessibilityCaptureResult.Rejected(AccessibilityCaptureRejection.INVALID_SUBJECT)
        }

        val fingerprints = frame.nodes.associate { node ->
            node.localId to sha256(
                listOf(
                    frame.packageName,
                    frame.windowId,
                    frame.displayId,
                    frame.taskId,
                    frame.snapshotVersion.toString(),
                    node.localId,
                    node.parentLocalId.orEmpty(),
                    node.role.orEmpty(),
                ).joinToString("\u001f"),
            )
        }

        val elements = try {
            frame.nodes.map { node ->
                val sanitized = privacyPolicy.sanitize(node.accessibleName, node.sensitivity)
                val fingerprint = fingerprints.getValue(node.localId)
                val parentFingerprint = node.parentLocalId?.let(fingerprints::getValue)
                DeviceUiElementSnapshot(
                    fingerprint = fingerprint,
                    parentFingerprint = parentFingerprint,
                    role = node.role,
                    accessibleName = sanitized.value,
                    visible = node.visible,
                    enabled = node.enabled,
                    editable = node.editable,
                    privacyClass = sanitized.privacyClass,
                    structuralDigestSha256 = sha256(
                        listOf(
                            fingerprint,
                            parentFingerprint.orEmpty(),
                            node.role.orEmpty(),
                            node.visible.toString(),
                            node.enabled.toString(),
                            node.editable.toString(),
                            sanitized.privacyClass.name,
                        ).joinToString("\u001f"),
                    ),
                )
            }
        } catch (_: IllegalArgumentException) {
            return AccessibilityCaptureResult.Rejected(AccessibilityCaptureRejection.INVALID_NODE)
        }

        val digestMaterial = elements.sortedBy(DeviceUiElementSnapshot::fingerprint).joinToString("\n") { element ->
            listOf(
                element.fingerprint,
                element.parentFingerprint.orEmpty(),
                element.role.orEmpty(),
                element.accessibleName,
                element.visible.toString(),
                element.enabled.toString(),
                element.editable.toString(),
                element.privacyClass.name,
                element.structuralDigestSha256,
            ).joinToString("\u001f")
        }

        return try {
            AccessibilityCaptureResult.Published(
                DeviceUiSnapshot(
                    subject = subject,
                    taskId = frame.taskId,
                    eventSequence = frame.eventSequence,
                    privacyPolicyVersion = frame.privacyPolicyVersion,
                    contentDigestSha256 = sha256(digestMaterial),
                    elements = elements,
                ),
            )
        } catch (_: IllegalArgumentException) {
            AccessibilityCaptureResult.Rejected(AccessibilityCaptureRejection.INVALID_SUBJECT)
        }
    }

    private fun validateDepth(nodesById: Map<String, RawAccessibilityNode>): DepthVerdict {
        for (node in nodesById.values) {
            val visited = mutableSetOf<String>()
            var current: RawAccessibilityNode? = node
            var depth = 0
            while (current != null) {
                if (!visited.add(current.localId)) return DepthVerdict.CYCLE
                val parentId = current.parentLocalId ?: break
                current = nodesById[parentId]
                depth += 1
                if (depth > maximumDepth) return DepthVerdict.TOO_DEEP
            }
        }
        return DepthVerdict.OK
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private enum class DepthVerdict { OK, TOO_DEEP, CYCLE }
}
