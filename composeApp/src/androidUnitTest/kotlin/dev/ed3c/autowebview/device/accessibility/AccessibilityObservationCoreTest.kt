package dev.ed3c.autowebview.device.accessibility

import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityCaptureRejection
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityCaptureResult
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityObservationFrame
import dev.ed3c.autowebview.device.accessibility.observation.BoundedAccessibilitySnapshotProjector
import dev.ed3c.autowebview.device.accessibility.observation.RawAccessibilityNode
import dev.ed3c.autowebview.device.accessibility.resolution.ExactAccessibilityTargetRequest
import dev.ed3c.autowebview.device.accessibility.resolution.ExactAccessibilityTargetResolution
import dev.ed3c.autowebview.device.accessibility.resolution.ExactAccessibilityTargetResolver
import dev.ed3c.autowebview.device.accessibility.resolution.OpaqueAccessibilityTargetTokenFactory
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.contract.DeviceUiPrivacyClass
import dev.ed3c.autowebview.device.privacy.AccessibilitySensitivity
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccessibilityObservationCoreTest {
    @Test
    fun sensitive_values_are_redacted_before_portable_snapshot_serialization() {
        val result = BoundedAccessibilitySnapshotProjector().project(
            frame(nodes = listOf(node("password", "hunter2", AccessibilitySensitivity.PASSWORD))),
        )
        val snapshot = assertIs<AccessibilityCaptureResult.Published>(result).snapshot
        assertEquals(DeviceUiPrivacyClass.SENSITIVE_REDACTED, snapshot.elements.single().privacyClass)
        assertEquals("", snapshot.elements.single().accessibleName)
        val encoded = Json.encodeToString(dev.ed3c.autowebview.device.contract.DeviceUiSnapshot.serializer(), snapshot)
        assertFalse(encoded.contains("hunter2"))
    }

    @Test
    fun duplicate_nodes_and_parent_cycles_fail_closed() {
        val projector = BoundedAccessibilitySnapshotProjector()
        val duplicate = projector.project(frame(nodes = listOf(node("same"), node("same"))))
        assertEquals(
            AccessibilityCaptureRejection.DUPLICATE_NODE_ID,
            assertIs<AccessibilityCaptureResult.Rejected>(duplicate).code,
        )

        val cycle = projector.project(
            frame(
                nodes = listOf(
                    node("a", parent = "b"),
                    node("b", parent = "a"),
                ),
            ),
        )
        assertEquals(
            AccessibilityCaptureRejection.CYCLIC_PARENTAGE,
            assertIs<AccessibilityCaptureResult.Rejected>(cycle).code,
        )
    }

    @Test
    fun exact_resolution_requires_current_subject_generation_and_freshness() {
        val snapshot = assertIs<AccessibilityCaptureResult.Published>(
            BoundedAccessibilitySnapshotProjector().project(frame(nodes = listOf(node("button")))),
        ).snapshot
        val target = DeviceTargetRef.UiTarget(snapshot.elements.single().fingerprint, snapshot.subject.snapshotVersion)
        val resolver = ExactAccessibilityTargetResolver(
            OpaqueAccessibilityTargetTokenFactory { _, _ -> "opaque-token-1" },
        )
        val resolved = resolver.resolve(snapshot, request(target))
        assertIs<ExactAccessibilityTargetResolution.Resolved>(resolved)

        val wrongTask = resolver.resolve(snapshot, request(target).copy(taskId = "other-task"))
        assertIs<ExactAccessibilityTargetResolution.Stale>(wrongTask)

        val stale = resolver.resolve(snapshot, request(target).copy(nowEpochMs = 20_001, maximumSnapshotAgeMs = 10_000))
        assertIs<ExactAccessibilityTargetResolution.Stale>(stale)
    }

    @Test
    fun semantic_mismatch_does_not_fall_back_to_first_clickable_node() {
        val snapshot = assertIs<AccessibilityCaptureResult.Published>(
            BoundedAccessibilitySnapshotProjector().project(frame(nodes = listOf(node("button", name = "Delete")))),
        ).snapshot
        val target = DeviceTargetRef.UiTarget(snapshot.elements.single().fingerprint, snapshot.subject.snapshotVersion)
        val resolver = ExactAccessibilityTargetResolver(
            OpaqueAccessibilityTargetTokenFactory { _, _ -> "opaque-token-1" },
        )
        val result = resolver.resolve(snapshot, request(target).copy(expectedAccessibleName = "Save"))
        assertIs<ExactAccessibilityTargetResolution.NotFound>(result)
    }

    @Test
    fun portable_snapshot_contains_no_coordinates_or_platform_handles() {
        val snapshot = assertIs<AccessibilityCaptureResult.Published>(
            BoundedAccessibilitySnapshotProjector().project(frame(nodes = listOf(node("button")))),
        ).snapshot
        val encoded = Json.encodeToString(dev.ed3c.autowebview.device.contract.DeviceUiSnapshot.serializer(), snapshot)
        assertTrue("AccessibilityNodeInfo" !in encoded)
        assertTrue("executionToken" !in encoded)
        assertTrue("\"x\"" !in encoded && "\"y\"" !in encoded)
    }

    private fun request(target: DeviceTargetRef.UiTarget) = ExactAccessibilityTargetRequest(
        target = target,
        packageName = "dev.ed3c.autowebview",
        windowId = "window-1",
        displayId = "display-0",
        taskId = "task-1",
        expectedRole = "button",
        expectedAccessibleName = "Button",
        nowEpochMs = 1_500,
        maximumSnapshotAgeMs = 10_000,
    )

    private fun frame(nodes: List<RawAccessibilityNode>) = AccessibilityObservationFrame(
        packageName = "dev.ed3c.autowebview",
        windowId = "window-1",
        displayId = "display-0",
        taskId = "task-1",
        snapshotVersion = 7,
        capturedAtEpochMs = 1_000,
        eventSequence = 42,
        privacyPolicyVersion = "privacy-v1",
        nodes = nodes,
    )

    private fun node(
        id: String,
        name: String = "Button",
        sensitivity: AccessibilitySensitivity = AccessibilitySensitivity.PUBLIC_METADATA,
        parent: String? = null,
    ) = RawAccessibilityNode(
        localId = id,
        parentLocalId = parent,
        role = "button",
        accessibleName = name,
        visible = true,
        enabled = true,
        editable = false,
        sensitivity = sensitivity,
    )
}
