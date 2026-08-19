package dev.ed3c.autowebview.device.accessibility.resolution

import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityObservationSession
import dev.ed3c.autowebview.device.contract.DeviceUiElementSnapshot
import dev.ed3c.autowebview.device.contract.DeviceUiSnapshot

class SessionBoundAccessibilityTargetTokenFactory(
    private val session: AccessibilityObservationSession,
    private val nowEpochMs: () -> Long,
    private val tokenTtlMs: Long = 2_000,
) : OpaqueAccessibilityTargetTokenFactory {
    override fun create(snapshot: DeviceUiSnapshot, element: DeviceUiElementSnapshot): String =
        session.issueToken(
            snapshot = snapshot,
            fingerprint = element.fingerprint,
            nowEpochMs = nowEpochMs(),
            ttlMs = tokenTtlMs,
        ).token
}
