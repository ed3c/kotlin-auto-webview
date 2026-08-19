package dev.ed3c.autowebview.device

import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.contract.DeviceUiElementSnapshot
import dev.ed3c.autowebview.device.contract.DeviceUiPrivacyClass
import dev.ed3c.autowebview.device.contract.DeviceUiSnapshot
import dev.ed3c.autowebview.device.contract.DeviceUiSnapshotSchema
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceUiSnapshotTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun sanitized_snapshot_round_trips_with_exact_subject_task_and_schema() {
        val snapshot = snapshot()
        val encoded = json.encodeToString(DeviceUiSnapshot.serializer(), snapshot)
        assertTrue(encoded.contains("\"schemaVersion\":\"${DeviceUiSnapshotSchema.VERSION}\""))
        assertTrue(encoded.contains("\"taskId\":\"task-42\""))
        assertEquals(snapshot, json.decodeFromString(DeviceUiSnapshot.serializer(), encoded))
    }

    @Test
    fun sensitive_metadata_cannot_cross_with_raw_accessible_name() {
        assertFailsWith<IllegalArgumentException> {
            element(
                fingerprint = "sensitive-1",
                accessibleName = "123456",
                privacyClass = DeviceUiPrivacyClass.SENSITIVE_REDACTED,
            )
        }
        val redacted = element(
            fingerprint = "sensitive-1",
            accessibleName = "",
            privacyClass = DeviceUiPrivacyClass.SENSITIVE_REDACTED,
        )
        assertEquals("", redacted.accessibleName)
    }

    @Test
    fun duplicate_or_dangling_fingerprints_fail_closed() {
        assertFailsWith<IllegalArgumentException> {
            snapshot(elements = listOf(element("node-1"), element("node-1")))
        }
        assertFailsWith<IllegalArgumentException> {
            snapshot(elements = listOf(element("node-1", parentFingerprint = "missing-parent")))
        }
    }

    @Test
    fun blank_or_command_like_task_identity_fails_closed() {
        assertFailsWith<IllegalArgumentException> { snapshot(taskId = "") }
        assertFailsWith<IllegalArgumentException> { snapshot(taskId = "task;rm") }
    }

    @Test
    fun freshness_is_exactly_bound_to_capture_time_and_maximum_age() {
        val snapshot = snapshot()
        assertTrue(snapshot.isFresh(nowEpochMs = 1_500, maximumAgeMs = 500))
        assertFalse(snapshot.isFresh(nowEpochMs = 1_501, maximumAgeMs = 500))
        assertFalse(snapshot.isFresh(nowEpochMs = 999, maximumAgeMs = 500))
        assertFalse(snapshot.isFresh(nowEpochMs = 1_500, maximumAgeMs = -1))
    }

    @Test
    fun target_candidates_require_the_same_snapshot_generation() {
        val snapshot = snapshot()
        assertEquals(1, snapshot.exactTargetCandidates(DeviceTargetRef.UiTarget("node-1", 7)).size)
        assertTrue(snapshot.exactTargetCandidates(DeviceTargetRef.UiTarget("node-1", 8)).isEmpty())
        assertTrue(snapshot.exactTargetCandidates(DeviceTargetRef.UiTarget("unknown-node", 7)).isEmpty())
    }

    @Test
    fun snapshot_has_no_executable_coordinates_or_platform_handles() {
        val encoded = json.encodeToString(DeviceUiSnapshot.serializer(), snapshot())
        assertFalse(encoded.contains("AccessibilityNodeInfo"))
        assertFalse(encoded.contains("executionToken"))
        assertFalse(encoded.contains("\"x\""))
        assertFalse(encoded.contains("\"y\""))
    }

    private fun snapshot(
        taskId: String = "task-42",
        elements: List<DeviceUiElementSnapshot> = listOf(element("node-1")),
    ) = DeviceUiSnapshot(
        subject = DeviceSubjectRef(
            packageName = "dev.ed3c.autowebview",
            windowId = "window-1",
            displayId = "display-0",
            snapshotVersion = 7,
            capturedAtEpochMs = 1_000,
        ),
        taskId = taskId,
        eventSequence = 42,
        privacyPolicyVersion = "privacy-v1",
        contentDigestSha256 = "d".repeat(64),
        elements = elements,
    )

    private fun element(
        fingerprint: String,
        parentFingerprint: String? = null,
        accessibleName: String = "button",
        privacyClass: DeviceUiPrivacyClass = DeviceUiPrivacyClass.PUBLIC_METADATA,
    ) = DeviceUiElementSnapshot(
        fingerprint = fingerprint,
        parentFingerprint = parentFingerprint,
        role = "button",
        accessibleName = accessibleName,
        visible = true,
        enabled = true,
        editable = false,
        privacyClass = privacyClass,
        structuralDigestSha256 = "e".repeat(64),
    )
}
