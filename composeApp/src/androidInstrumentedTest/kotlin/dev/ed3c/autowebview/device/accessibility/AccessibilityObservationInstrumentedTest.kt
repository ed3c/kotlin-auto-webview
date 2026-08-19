package dev.ed3c.autowebview.device.accessibility

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityInvalidationReason
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityObservationFrame
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityObservationSession
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityOpaqueTokenSource
import dev.ed3c.autowebview.device.accessibility.observation.BoundedAccessibilitySnapshotProjector
import dev.ed3c.autowebview.device.accessibility.observation.RawAccessibilityNode
import dev.ed3c.autowebview.device.accessibility.resolution.ExactAccessibilityTargetRequest
import dev.ed3c.autowebview.device.accessibility.resolution.ExactAccessibilityTargetResolution
import dev.ed3c.autowebview.device.accessibility.resolution.ExactAccessibilityTargetResolver
import dev.ed3c.autowebview.device.accessibility.resolution.SessionBoundAccessibilityTargetTokenFactory
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.contract.DeviceUiPrivacyClass
import dev.ed3c.autowebview.device.privacy.AccessibilitySensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityObservationInstrumentedTest {
    @Test
    fun android_runtime_fixture_is_present_but_accessibility_service_liveness_is_not_claimed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("dev.ed3c.autowebview", context.packageName)
        assertTrue(Build.VERSION.SDK_INT >= 24)

        val session = session()
        assertEquals(null, session.captureLease())
    }

    @Test
    fun sensitive_fixture_classes_are_redacted_before_portable_snapshot() {
        val sensitivities = listOf(
            AccessibilitySensitivity.PASSWORD,
            AccessibilitySensitivity.PAYMENT,
            AccessibilitySensitivity.OTP,
            AccessibilitySensitivity.SECRET,
            AccessibilitySensitivity.PRIVATE_MESSAGE,
        )
        val nodes = sensitivities.mapIndexed { index, sensitivity ->
            RawAccessibilityNode(
                localId = "sensitive-$index",
                role = if (index == 1) "edit-text" else "button",
                accessibleName = "forbidden-value-$index",
                visible = true,
                enabled = true,
                editable = true,
                sensitivity = sensitivity,
            )
        } + listOf(
            RawAccessibilityNode(
                localId = "recycler-root",
                role = "list",
                accessibleName = "Items",
                visible = true,
                enabled = true,
                editable = false,
                sensitivity = AccessibilitySensitivity.PUBLIC_METADATA,
            ),
            RawAccessibilityNode(
                localId = "compose-button",
                parentLocalId = "recycler-root",
                role = "button",
                accessibleName = "Continue",
                visible = true,
                enabled = true,
                editable = false,
                sensitivity = AccessibilitySensitivity.PUBLIC_METADATA,
            ),
            RawAccessibilityNode(
                localId = "webview-button",
                parentLocalId = "recycler-root",
                role = "web-view",
                accessibleName = "Continue",
                visible = true,
                enabled = true,
                editable = false,
                sensitivity = AccessibilitySensitivity.PUBLIC_METADATA,
            ),
        )
        val snapshot = published(frame(nodes = nodes))
        val sensitive = snapshot.elements.take(sensitivities.size)
        assertTrue(sensitive.all { it.privacyClass == DeviceUiPrivacyClass.SENSITIVE_REDACTED })
        assertTrue(sensitive.all { it.accessibleName.isEmpty() })
        assertFalse(snapshot.elements.any { it.accessibleName.startsWith("forbidden-value") })
    }

    @Test
    fun duplicate_semantic_labels_never_create_first_match_authority() {
        val session = session()
        session.connect()
        val snapshot = published(
            frame(
                snapshotVersion = session.generation,
                eventSequence = session.eventSequence,
                nodes = listOf(
                    publicNode("compose-button", "button", "Continue"),
                    publicNode("webview-button", "web-view", "Continue"),
                ),
            ),
        )
        val resolver = resolver(session)
        val unknown = resolver.resolve(
            snapshot,
            request(snapshot, DeviceTargetRef.UiTarget("not-a-real-fingerprint", snapshot.subject.snapshotVersion)),
        )
        assertTrue(unknown is ExactAccessibilityTargetResolution.NotFound)

        val exact = snapshot.elements.first()
        val resolved = resolver.resolve(
            snapshot,
            request(snapshot, DeviceTargetRef.UiTarget(exact.fingerprint, snapshot.subject.snapshotVersion)),
        )
        assertTrue(resolved is ExactAccessibilityTargetResolution.Resolved)
        assertEquals(exact.fingerprint, (resolved as ExactAccessibilityTargetResolution.Resolved).fingerprint)
    }

    @Test
    fun subject_task_generation_and_time_mismatches_fail_closed() {
        val session = session()
        session.connect()
        val snapshot = published(
            frame(
                snapshotVersion = session.generation,
                eventSequence = session.eventSequence,
                nodes = listOf(publicNode("button-1", "button", "Continue")),
            ),
        )
        val target = DeviceTargetRef.UiTarget(snapshot.elements.single().fingerprint, snapshot.subject.snapshotVersion)
        val resolver = resolver(session)

        assertTrue(
            resolver.resolve(snapshot, request(snapshot, target).copy(packageName = "com.example.other"))
                is ExactAccessibilityTargetResolution.Stale,
        )
        assertTrue(
            resolver.resolve(snapshot, request(snapshot, target).copy(taskId = "task-other"))
                is ExactAccessibilityTargetResolution.Stale,
        )
        assertTrue(
            resolver.resolve(snapshot, request(snapshot, target.copy(snapshotVersion = target.snapshotVersion + 1)))
                is ExactAccessibilityTargetResolution.Stale,
        )
        assertTrue(
            resolver.resolve(snapshot, request(snapshot, target).copy(nowEpochMs = 20_001, maximumSnapshotAgeMs = 1_000))
                is ExactAccessibilityTargetResolution.Stale,
        )
    }

    @Test
    fun every_authority_relevant_invalidation_revokes_the_process_local_token() {
        for (reason in AccessibilityInvalidationReason.entries) {
            val session = session()
            session.connect()
            val snapshot = published(
                frame(
                    snapshotVersion = session.generation,
                    eventSequence = session.eventSequence,
                    nodes = listOf(publicNode("button-1", "button", "Continue")),
                ),
            )
            val binding = session.issueToken(snapshot, snapshot.elements.single().fingerprint, 1_100, 2_000)
            assertTrue(session.validateToken(binding.token, snapshot, 1_200))
            session.invalidate(reason)
            assertFalse(session.validateToken(binding.token, snapshot, 1_200))
        }
    }

    private fun session() = AccessibilityObservationSession(
        AccessibilityOpaqueTokenSource { "instrumented-opaque-token" },
    )

    private fun resolver(session: AccessibilityObservationSession) = ExactAccessibilityTargetResolver(
        SessionBoundAccessibilityTargetTokenFactory(session, nowEpochMs = { 1_100 }, tokenTtlMs = 2_000),
    )

    private fun request(
        snapshot: dev.ed3c.autowebview.device.contract.DeviceUiSnapshot,
        target: DeviceTargetRef.UiTarget,
    ) = ExactAccessibilityTargetRequest(
        target = target,
        packageName = snapshot.subject.packageName,
        windowId = snapshot.subject.windowId,
        displayId = snapshot.subject.displayId,
        taskId = snapshot.taskId,
        nowEpochMs = 1_100,
        maximumSnapshotAgeMs = 5_000,
        tokenTtlMs = 2_000,
    )

    private fun frame(
        snapshotVersion: Long = 1,
        eventSequence: Long = 1,
        nodes: List<RawAccessibilityNode>,
    ) = AccessibilityObservationFrame(
        packageName = "dev.ed3c.autowebview",
        windowId = "window-1",
        displayId = "display-0",
        taskId = "task-1",
        snapshotVersion = snapshotVersion,
        capturedAtEpochMs = 1_000,
        eventSequence = eventSequence,
        privacyPolicyVersion = "privacy-v1",
        nodes = nodes,
    )

    private fun publicNode(localId: String, role: String, name: String) = RawAccessibilityNode(
        localId = localId,
        role = role,
        accessibleName = name,
        visible = true,
        enabled = true,
        editable = false,
        sensitivity = AccessibilitySensitivity.PUBLIC_METADATA,
    )

    private fun published(frame: AccessibilityObservationFrame) =
        (BoundedAccessibilitySnapshotProjector().project(frame) as dev.ed3c.autowebview.device.accessibility.observation.AccessibilityCaptureResult.Published).snapshot
}
