package dev.ed3c.autowebview.device.accessibility

import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityInvalidationReason
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityObservationSession
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityOpaqueTokenSource
import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceUiElementSnapshot
import dev.ed3c.autowebview.device.contract.DeviceUiPrivacyClass
import dev.ed3c.autowebview.device.contract.DeviceUiSnapshot
import dev.ed3c.autowebview.device.privacy.AccessibilityNodeSensitivityClassifier
import dev.ed3c.autowebview.device.privacy.AccessibilityNodeSensitivityMetadata
import dev.ed3c.autowebview.device.privacy.AccessibilitySensitivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccessibilityObservationSessionTest {
    @Test
    fun disconnected_service_has_no_capture_lease_and_connect_advances_generation() {
        val session = session()
        assertNull(session.captureLease())
        val first = session.connect()
        assertEquals(1, first.generation)
        assertNotNull(session.captureLease())
        session.disconnect()
        assertNull(session.captureLease())
        val second = session.connect()
        assertTrue(second.generation > first.generation)
    }

    @Test
    fun every_authority_relevant_event_invalidates_outstanding_target_tokens() {
        for (reason in AccessibilityInvalidationReason.entries) {
            val session = session()
            session.connect()
            val snapshot = snapshot(session.generation, session.eventSequence)
            val binding = session.issueToken(snapshot, "node-1", 1_000, 2_000)
            assertTrue(session.validateToken(binding.token, snapshot, 1_100))
            session.invalidate(reason)
            assertFalse(session.validateToken(binding.token, snapshot, 1_100))
            assertEquals(0, session.activeTokenCount())
        }
    }

    @Test
    fun stale_generation_or_expired_token_cannot_be_reused() {
        val session = session()
        session.connect()
        val snapshot = snapshot(session.generation, session.eventSequence)
        val binding = session.issueToken(snapshot, "node-1", 1_000, 100)
        assertFalse(session.validateToken(binding.token, snapshot, 1_101))
        session.invalidate(AccessibilityInvalidationReason.CONTENT_CHANGED)
        assertFalse(session.validateToken(binding.token, snapshot, 1_050))
    }

    @Test
    fun classifier_marks_sensitive_metadata_before_text_can_be_read() {
        val classifier = AccessibilityNodeSensitivityClassifier()
        assertEquals(
            AccessibilitySensitivity.PASSWORD,
            classifier.classify(AccessibilityNodeSensitivityMetadata(password = true, editable = true)),
        )
        assertEquals(
            AccessibilitySensitivity.PAYMENT,
            classifier.classify(
                AccessibilityNodeSensitivityMetadata(
                    password = false,
                    editable = true,
                    viewIdResourceName = "com.example:id/card_cvv",
                ),
            ),
        )
        assertEquals(
            AccessibilitySensitivity.OTP,
            classifier.classify(
                AccessibilityNodeSensitivityMetadata(
                    password = false,
                    editable = true,
                    viewIdResourceName = "com.example:id/otp_code",
                ),
            ),
        )
        assertEquals(
            AccessibilitySensitivity.PRIVATE_MESSAGE,
            classifier.classify(
                AccessibilityNodeSensitivityMetadata(
                    password = false,
                    editable = true,
                    viewIdResourceName = "com.example:id/chat_message",
                ),
            ),
        )
        assertEquals(
            AccessibilitySensitivity.USER_CONTENT,
            classifier.classify(AccessibilityNodeSensitivityMetadata(password = false, editable = true)),
        )
    }

    private fun session() = AccessibilityObservationSession(
        AccessibilityOpaqueTokenSource { "opaque-token" },
    )

    private fun snapshot(generation: Long, eventSequence: Long) = DeviceUiSnapshot(
        subject = DeviceSubjectRef(
            packageName = "dev.ed3c.autowebview",
            windowId = "window-1",
            displayId = "display-0",
            snapshotVersion = generation,
            capturedAtEpochMs = 900,
        ),
        taskId = "task-1",
        eventSequence = eventSequence,
        privacyPolicyVersion = "privacy-v1",
        contentDigestSha256 = "a".repeat(64),
        elements = listOf(
            DeviceUiElementSnapshot(
                fingerprint = "node-1",
                role = "button",
                accessibleName = "Button",
                visible = true,
                enabled = true,
                editable = false,
                privacyClass = DeviceUiPrivacyClass.PUBLIC_METADATA,
                structuralDigestSha256 = "b".repeat(64),
            ),
        ),
    )
}
