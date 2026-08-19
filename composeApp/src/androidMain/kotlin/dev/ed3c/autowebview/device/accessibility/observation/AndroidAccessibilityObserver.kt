package dev.ed3c.autowebview.device.accessibility.observation

import android.view.accessibility.AccessibilityWindowInfo
import dev.ed3c.autowebview.device.contract.DeviceUiSnapshot

sealed interface AndroidAccessibilityObservationResult {
    data class Published(val snapshot: DeviceUiSnapshot) : AndroidAccessibilityObservationResult
    data class Absent(val reason: AndroidAccessibilityAbsentReason) : AndroidAccessibilityObservationResult
    data class ReadRejected(val reason: AndroidAccessibilityReadRejection) : AndroidAccessibilityObservationResult
    data class SnapshotRejected(val reason: AccessibilityCaptureRejection) : AndroidAccessibilityObservationResult
}

class AndroidAccessibilityObserver(
    private val reader: AndroidAccessibilityTreeReader,
    private val projector: BoundedAccessibilitySnapshotProjector = BoundedAccessibilitySnapshotProjector(),
) {
    fun capture(
        window: AccessibilityWindowInfo?,
        request: AndroidAccessibilityReadRequest,
    ): AndroidAccessibilityObservationResult = when (val read = reader.read(window, request)) {
        is AndroidAccessibilityReadResult.Absent -> AndroidAccessibilityObservationResult.Absent(read.reason)
        is AndroidAccessibilityReadResult.Rejected -> AndroidAccessibilityObservationResult.ReadRejected(read.reason)
        is AndroidAccessibilityReadResult.Published -> projectFrame(read.frame)
    }

    fun projectFrame(frame: AccessibilityObservationFrame): AndroidAccessibilityObservationResult =
        when (val projected = projector.project(frame)) {
            is AccessibilityCaptureResult.Published -> AndroidAccessibilityObservationResult.Published(projected.snapshot)
            is AccessibilityCaptureResult.Rejected -> AndroidAccessibilityObservationResult.SnapshotRejected(projected.code)
        }
}
