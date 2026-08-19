package dev.ed3c.autowebview.device.accessibility.observation

import dev.ed3c.autowebview.device.contract.DeviceUiSnapshot
import java.util.UUID

enum class AccessibilityServiceConnectionState {
    DISCONNECTED,
    CONNECTED,
}

enum class AccessibilityInvalidationReason {
    USER_INTERACTION,
    WINDOW_CHANGED,
    CONTENT_CHANGED,
    PACKAGE_CHANGED,
    SCREEN_LOCKED,
    SERVICE_DISCONNECTED,
    PROCESS_RESTARTED,
}

data class AccessibilityCaptureLease(
    val generation: Long,
    val eventSequence: Long,
)

data class AccessibilityTargetTokenBinding(
    val token: String,
    val packageName: String,
    val windowId: String,
    val displayId: String,
    val taskId: String,
    val snapshotVersion: Long,
    val eventSequence: Long,
    val generation: Long,
    val fingerprint: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

fun interface AccessibilityOpaqueTokenSource {
    fun nextToken(): String
}

class AccessibilityObservationSession(
    private val tokenSource: AccessibilityOpaqueTokenSource = AccessibilityOpaqueTokenSource { UUID.randomUUID().toString() },
) {
    var connectionState: AccessibilityServiceConnectionState = AccessibilityServiceConnectionState.DISCONNECTED
        private set
    var generation: Long = 0
        private set
    var eventSequence: Long = 0
        private set
    var lastInvalidationReason: AccessibilityInvalidationReason? = null
        private set

    private val tokens = mutableMapOf<String, AccessibilityTargetTokenBinding>()

    fun connect(): AccessibilityCaptureLease {
        if (connectionState == AccessibilityServiceConnectionState.DISCONNECTED) {
            generation += 1
            eventSequence += 1
        }
        connectionState = AccessibilityServiceConnectionState.CONNECTED
        lastInvalidationReason = null
        return AccessibilityCaptureLease(generation, eventSequence)
    }

    fun disconnect() {
        invalidate(AccessibilityInvalidationReason.SERVICE_DISCONNECTED)
        connectionState = AccessibilityServiceConnectionState.DISCONNECTED
    }

    fun captureLease(): AccessibilityCaptureLease? =
        if (connectionState == AccessibilityServiceConnectionState.CONNECTED) {
            AccessibilityCaptureLease(generation, eventSequence)
        } else {
            null
        }

    fun invalidate(reason: AccessibilityInvalidationReason) {
        generation += 1
        eventSequence += 1
        lastInvalidationReason = reason
        tokens.clear()
    }

    fun issueToken(
        snapshot: DeviceUiSnapshot,
        fingerprint: String,
        nowEpochMs: Long,
        ttlMs: Long,
    ): AccessibilityTargetTokenBinding {
        require(connectionState == AccessibilityServiceConnectionState.CONNECTED) {
            "Accessibility service is not connected"
        }
        require(ttlMs in 1..30_000) { "Target token TTL is outside the bounded range" }
        require(nowEpochMs >= 0) { "Target token time cannot be negative" }
        require(snapshot.subject.snapshotVersion == generation) { "Snapshot generation is no longer current" }
        require(snapshot.eventSequence == eventSequence) { "Snapshot event sequence is no longer current" }
        require(snapshot.elements.any { it.fingerprint == fingerprint }) { "Target fingerprint is not in the exact snapshot" }
        val token = tokenSource.nextToken()
        require(token.length in 1..256 && token.none(Char::isWhitespace) && token.none(Char::isISOControl)) {
            "Target token must be a bounded opaque value"
        }
        val binding = AccessibilityTargetTokenBinding(
            token = token,
            packageName = snapshot.subject.packageName,
            windowId = snapshot.subject.windowId,
            displayId = snapshot.subject.displayId,
            taskId = snapshot.taskId,
            snapshotVersion = snapshot.subject.snapshotVersion,
            eventSequence = snapshot.eventSequence,
            generation = generation,
            fingerprint = fingerprint,
            issuedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = nowEpochMs + ttlMs,
        )
        tokens[token] = binding
        return binding
    }

    fun validateToken(
        token: String,
        snapshot: DeviceUiSnapshot,
        nowEpochMs: Long,
    ): Boolean {
        if (connectionState != AccessibilityServiceConnectionState.CONNECTED) return false
        val binding = tokens[token] ?: return false
        if (nowEpochMs < binding.issuedAtEpochMs || nowEpochMs > binding.expiresAtEpochMs) return false
        return binding.generation == generation &&
            binding.eventSequence == eventSequence &&
            binding.packageName == snapshot.subject.packageName &&
            binding.windowId == snapshot.subject.windowId &&
            binding.displayId == snapshot.subject.displayId &&
            binding.taskId == snapshot.taskId &&
            binding.snapshotVersion == snapshot.subject.snapshotVersion &&
            binding.eventSequence == snapshot.eventSequence &&
            snapshot.elements.any { it.fingerprint == binding.fingerprint }
    }

    fun activeTokenCount(): Int = tokens.size
}
