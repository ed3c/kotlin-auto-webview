package dev.ed3c.autowebview.device.accessibility

import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityObservationFrame
import dev.ed3c.autowebview.device.accessibility.observation.AndroidAccessibilityObservationResult
import dev.ed3c.autowebview.device.accessibility.observation.AndroidAccessibilityObserver
import dev.ed3c.autowebview.device.accessibility.observation.AndroidAccessibilityTreeReader
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityObservationSession
import dev.ed3c.autowebview.device.accessibility.observation.RawAccessibilityNode
import dev.ed3c.autowebview.device.contract.DeviceUiPrivacyClass
import dev.ed3c.autowebview.device.privacy.AccessibilitySensitivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AndroidAccessibilityObserverProjectionTest {
    @Test
    fun live_reader_frame_projection_reuses_the_same_privacy_boundary() {
        val observer = AndroidAccessibilityObserver(
            reader = AndroidAccessibilityTreeReader(AccessibilityObservationSession()),
        )
        val result = observer.projectFrame(
            AccessibilityObservationFrame(
                packageName = "dev.ed3c.autowebview",
                windowId = "window-1",
                displayId = "display-0",
                taskId = "task-1",
                snapshotVersion = 1,
                capturedAtEpochMs = 1_000,
                eventSequence = 1,
                privacyPolicyVersion = "privacy-v1",
                nodes = listOf(
                    RawAccessibilityNode(
                        localId = "node-1",
                        role = "edit-text",
                        accessibleName = "super-secret",
                        visible = true,
                        enabled = true,
                        editable = true,
                        sensitivity = AccessibilitySensitivity.SECRET,
                    ),
                ),
            ),
        )
        val snapshot = assertIs<AndroidAccessibilityObservationResult.Published>(result).snapshot
        assertEquals(DeviceUiPrivacyClass.SENSITIVE_REDACTED, snapshot.elements.single().privacyClass)
        assertEquals("", snapshot.elements.single().accessibleName)
    }
}
