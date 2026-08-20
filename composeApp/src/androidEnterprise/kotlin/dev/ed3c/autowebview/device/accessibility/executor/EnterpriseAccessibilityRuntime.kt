package dev.ed3c.autowebview.device.accessibility.executor

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityInvalidationReason
import dev.ed3c.autowebview.device.accessibility.observation.AccessibilityObservationSession
import dev.ed3c.autowebview.device.accessibility.observation.AndroidAccessibilityObservationResult
import dev.ed3c.autowebview.device.accessibility.observation.AndroidAccessibilityObserver
import dev.ed3c.autowebview.device.accessibility.observation.AndroidAccessibilityReadRequest
import dev.ed3c.autowebview.device.accessibility.observation.AndroidAccessibilityTreeReader
import dev.ed3c.autowebview.device.contract.DeviceActionCommand
import dev.ed3c.autowebview.device.contract.DeviceSubjectRef
import dev.ed3c.autowebview.device.contract.DeviceTargetRef
import dev.ed3c.autowebview.device.contract.DeviceUiElementSnapshot
import dev.ed3c.autowebview.device.contract.DeviceUiPrivacyClass
import dev.ed3c.autowebview.device.contract.DeviceUiSnapshot
import dev.ed3c.autowebview.device.executor.EnterpriseAccessibilityDispatchDecision
import dev.ed3c.autowebview.device.executor.EnterpriseAccessibilityDispatchPolicy
import dev.ed3c.autowebview.device.privacy.AccessibilityNodeSensitivityClassifier
import dev.ed3c.autowebview.device.privacy.AccessibilityNodeSensitivityMetadata
import dev.ed3c.autowebview.device.privacy.AccessibilityPrivacyPolicy
import dev.ed3c.autowebview.device.runtime.DeviceDispatchAdmission
import dev.ed3c.autowebview.device.runtime.DevicePlatformDispatchEvidence
import dev.ed3c.autowebview.device.runtime.DevicePlatformDispatchResult
import dev.ed3c.autowebview.device.runtime.DevicePlatformDispatcher
import dev.ed3c.autowebview.device.runtime.DeviceResolvedTarget
import dev.ed3c.autowebview.device.runtime.DeviceTargetResolution
import dev.ed3c.autowebview.device.runtime.DeviceTargetResolver
import java.security.MessageDigest
import java.util.UUID

/**
 * Human-provisioned exact package allowlist for the enterprise artifact.
 *
 * It is deliberately in-memory and empty by default. No transport, model, MCP producer or
 * Accessibility event can widen this set. A future managed-device UI may bind its Human-owned
 * policy source here without changing the execution adapter.
 */
internal object EnterpriseAccessibilityProvisioning {
    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    @Volatile
    private var managedPackages: Set<String> = emptySet()

    fun replaceManagedPackages(packages: Set<String>) {
        require(packages.size <= 64) { "Managed package allowlist is unbounded" }
        require(packages.all { it.length <= 255 && packageName.matches(it) }) {
            "Managed package allowlist requires exact Android package names"
        }
        managedPackages = packages.toSet()
    }

    fun contains(packageName: String): Boolean = packageName in managedPackages
}

internal sealed interface EnterpriseAccessibilityObservationResult {
    data class Published(val snapshot: DeviceUiSnapshot) : EnterpriseAccessibilityObservationResult
    data class Unavailable(val code: String) : EnterpriseAccessibilityObservationResult
    data class Rejected(val code: String) : EnterpriseAccessibilityObservationResult
}

/**
 * Enterprise-only Android AccessibilityService host.
 *
 * The service does not accept raw action requests. It exposes only the typed DeviceTargetResolver
 * and DevicePlatformDispatcher consumed by the common DeviceAutomationRuntime. Accessibility
 * callback success is therefore dispatch evidence; the common deterministic verifier remains the
 * only owner of APPLIED/NONE/UNKNOWN.
 */
class EnterpriseAccessibilityService : AccessibilityService() {
    private val session = AccessibilityObservationSession()
    private val runtimeDelegate by lazy { EnterpriseAccessibilityRuntime(this, session) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        session.connect()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        runtimeDelegate.invalidateSnapshot()
        session.invalidate(event.invalidationReason())
    }

    override fun onInterrupt() {
        runtimeDelegate.invalidateSnapshot()
        session.invalidate(AccessibilityInvalidationReason.USER_INTERACTION)
    }

    override fun onDestroy() {
        runtimeDelegate.invalidateSnapshot()
        session.disconnect()
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    internal fun runtime(): EnterpriseAccessibilityRuntime = runtimeDelegate

    private fun AccessibilityEvent.invalidationReason(): AccessibilityInvalidationReason = when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        -> AccessibilityInvalidationReason.WINDOW_CHANGED

        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED,
        -> AccessibilityInvalidationReason.USER_INTERACTION

        else -> AccessibilityInvalidationReason.CONTENT_CHANGED
    }

    companion object {
        @Volatile
        private var activeService: EnterpriseAccessibilityService? = null

        internal fun activeRuntime(): EnterpriseAccessibilityRuntime? = activeService?.runtimeDelegate
    }
}

internal class EnterpriseAccessibilityRuntime(
    private val service: EnterpriseAccessibilityService,
    private val session: AccessibilityObservationSession,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val observer = AndroidAccessibilityObserver(AndroidAccessibilityTreeReader(session))
    private val dispatchPolicy = EnterpriseAccessibilityDispatchPolicy()
    private val classifier = AccessibilityNodeSensitivityClassifier()
    private val privacyPolicy = AccessibilityPrivacyPolicy()
    private val executionBindings = mutableMapOf<String, ExecutionBinding>()

    @Volatile
    private var latestSnapshot: DeviceUiSnapshot? = null

    val targetResolver: DeviceTargetResolver = DeviceTargetResolver { proposal, now ->
        if (!EnterpriseAccessibilityProvisioning.contains(proposal.subject.packageName)) {
            return@DeviceTargetResolver DeviceTargetResolution.Stale("package-not-managed")
        }
        val target = proposal.target as? DeviceTargetRef.UiTarget
            ?: return@DeviceTargetResolver DeviceTargetResolution.NotFound
        val snapshot = latestSnapshot
            ?: return@DeviceTargetResolver DeviceTargetResolution.Stale("snapshot-absent")
        if (snapshot.subject != proposal.subject) {
            return@DeviceTargetResolver DeviceTargetResolution.Stale("subject-mismatch")
        }
        if (!snapshot.isFresh(now, proposal.maximumSnapshotAgeMs)) {
            return@DeviceTargetResolver DeviceTargetResolution.Stale("snapshot-expired")
        }
        val candidates = snapshot.exactTargetCandidates(target).filter {
            it.visible && it.enabled && it.privacyClass != DeviceUiPrivacyClass.SENSITIVE_REDACTED
        }
        when (candidates.size) {
            0 -> DeviceTargetResolution.NotFound
            1 -> issueResolvedTarget(proposal.expiresAtEpochMs, snapshot, candidates.single(), now)
            else -> DeviceTargetResolution.Ambiguous
        }
    }

    val platformDispatcher: DevicePlatformDispatcher = DevicePlatformDispatcher { command, admission ->
        dispatch(command, admission)
    }

    fun observeManagedPackage(
        taskId: String,
        packageName: String,
        capturedAtEpochMs: Long = nowEpochMs(),
    ): EnterpriseAccessibilityObservationResult {
        if (!EnterpriseAccessibilityProvisioning.contains(packageName)) {
            return EnterpriseAccessibilityObservationResult.Rejected("package-not-managed")
        }
        val windows = matchingWindows(packageName)
        if (windows.isEmpty()) return EnterpriseAccessibilityObservationResult.Unavailable("window-unavailable")
        if (windows.size != 1) return EnterpriseAccessibilityObservationResult.Rejected("window-ambiguous")
        return captureWindow(
            window = windows.single(),
            taskId = taskId,
            packageName = packageName,
            capturedAtEpochMs = capturedAtEpochMs,
        )
    }

    fun invalidateSnapshot() {
        latestSnapshot = null
        executionBindings.clear()
    }

    private fun issueResolvedTarget(
        proposalExpiresAtEpochMs: Long,
        snapshot: DeviceUiSnapshot,
        element: DeviceUiElementSnapshot,
        now: Long,
    ): DeviceTargetResolution {
        val ttl = minOf(2_000L, proposalExpiresAtEpochMs - now)
        if (ttl <= 0L) return DeviceTargetResolution.Stale("proposal-expired")
        return try {
            val tokenBinding = session.issueToken(
                snapshot = snapshot,
                fingerprint = element.fingerprint,
                nowEpochMs = now,
                ttlMs = ttl,
            )
            executionBindings[tokenBinding.token] = ExecutionBinding(
                token = tokenBinding.token,
                taskId = snapshot.taskId,
                subject = snapshot.subject,
                targetFingerprint = element.fingerprint,
                structuralDigestSha256 = element.structuralDigestSha256,
                accessibleNameDigestSha256 = sha256(element.accessibleName),
                privacyClass = element.privacyClass,
                issuedAtEpochMs = tokenBinding.issuedAtEpochMs,
                expiresAtEpochMs = tokenBinding.expiresAtEpochMs,
            )
            DeviceTargetResolution.Resolved(
                DeviceResolvedTarget(
                    subject = snapshot.subject,
                    target = DeviceTargetRef.UiTarget(
                        fingerprint = element.fingerprint,
                        snapshotVersion = snapshot.subject.snapshotVersion,
                    ),
                    resolvedTargetToken = tokenBinding.token,
                    tokenDigestSha256 = sha256(tokenBinding.token),
                    issuedAtEpochMs = tokenBinding.issuedAtEpochMs,
                    expiresAtEpochMs = tokenBinding.expiresAtEpochMs,
                ),
            )
        } catch (_: IllegalArgumentException) {
            DeviceTargetResolution.Stale("token-issue-rejected")
        } catch (_: IllegalStateException) {
            DeviceTargetResolution.Stale("session-not-connected")
        }
    }

    private fun dispatch(
        command: DeviceActionCommand,
        admission: DeviceDispatchAdmission,
    ): DevicePlatformDispatchResult {
        val policy = dispatchPolicy.evaluate(command)
        if (policy != EnterpriseAccessibilityDispatchDecision.ADMITTED) {
            return DevicePlatformDispatchResult.NotDispatched("policy-${canonical(policy.name)}")
        }
        if (admission.proposalId != command.proposalId) {
            return DevicePlatformDispatchResult.NotDispatched("admission-proposal-mismatch")
        }
        if (sha256(command.resolvedTargetToken) != admission.targetTokenDigestSha256) {
            return DevicePlatformDispatchResult.NotDispatched("admission-token-mismatch")
        }
        val binding = executionBindings[command.resolvedTargetToken]
            ?: return DevicePlatformDispatchResult.NotDispatched("target-token-absent")
        val target = command.target as DeviceTargetRef.UiTarget
        if (
            binding.subject != command.subject ||
            binding.targetFingerprint != target.fingerprint ||
            target.snapshotVersion != command.subject.snapshotVersion
        ) {
            return DevicePlatformDispatchResult.NotDispatched("target-binding-mismatch")
        }
        if (!EnterpriseAccessibilityProvisioning.contains(command.subject.packageName)) {
            return DevicePlatformDispatchResult.NotDispatched("package-not-managed")
        }

        val now = nowEpochMs()
        if (now < binding.issuedAtEpochMs || now > binding.expiresAtEpochMs) {
            return DevicePlatformDispatchResult.NotDispatched("target-token-expired")
        }
        val snapshot = captureExactSnapshot(binding.taskId, command.subject, now)
            ?: return DevicePlatformDispatchResult.NotDispatched("fresh-snapshot-unavailable")
        if (!session.validateToken(command.resolvedTargetToken, snapshot, now)) {
            return DevicePlatformDispatchResult.NotDispatched("target-token-stale")
        }
        val currentElement = snapshot.exactTargetCandidates(target).singleOrNull()
            ?: return DevicePlatformDispatchResult.NotDispatched("target-not-exact")
        if (!currentElement.visible || !currentElement.enabled) {
            return DevicePlatformDispatchResult.NotDispatched("target-not-interactable")
        }
        if (currentElement.privacyClass == DeviceUiPrivacyClass.SENSITIVE_REDACTED) {
            return DevicePlatformDispatchResult.NotDispatched("target-sensitive")
        }
        if (
            currentElement.structuralDigestSha256 != binding.structuralDigestSha256 ||
            sha256(currentElement.accessibleName) != binding.accessibleNameDigestSha256 ||
            currentElement.privacyClass != binding.privacyClass
        ) {
            return DevicePlatformDispatchResult.NotDispatched("target-semantic-drift")
        }

        val window = exactWindow(command.subject)
            ?: return DevicePlatformDispatchResult.NotDispatched("exact-window-unavailable")
        val matches = findNativeMatches(
            window = window,
            subject = command.subject,
            taskId = binding.taskId,
            targetFingerprint = target.fingerprint,
        )
        if (matches.size != 1) {
            matches.forEach { releaseNode(it.node) }
            return DevicePlatformDispatchResult.NotDispatched(
                if (matches.isEmpty()) "native-target-not-found" else "native-target-ambiguous",
            )
        }

        val match = matches.single()
        return try {
            if (
                match.structuralDigestSha256 != binding.structuralDigestSha256 ||
                match.accessibleNameDigestSha256 != binding.accessibleNameDigestSha256 ||
                match.privacyClass != binding.privacyClass ||
                !match.node.isVisibleToUser ||
                !match.node.isEnabled ||
                !match.node.isClickable
            ) {
                DevicePlatformDispatchResult.NotDispatched("native-target-revalidation-failed")
            } else if (!session.validateToken(command.resolvedTargetToken, snapshot, nowEpochMs())) {
                DevicePlatformDispatchResult.NotDispatched("target-token-invalidated-before-dispatch")
            } else {
                val accepted = match.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (accepted) {
                    DevicePlatformDispatchResult.Dispatched(
                        DevicePlatformDispatchEvidence(
                            dispatchId = UUID.randomUUID().toString(),
                            platformCallbackAccepted = true,
                        ),
                    )
                } else {
                    DevicePlatformDispatchResult.FailureBeforeEffect("accessibility-click-rejected")
                }
            }
        } catch (_: Throwable) {
            DevicePlatformDispatchResult.FailureUnknown("accessibility-click-exception")
        } finally {
            releaseNode(match.node)
        }
    }

    private fun captureExactSnapshot(
        taskId: String,
        subject: DeviceSubjectRef,
        capturedAtEpochMs: Long,
    ): DeviceUiSnapshot? {
        val window = exactWindow(subject) ?: return null
        return when (
            val captured = captureWindow(
                window = window,
                taskId = taskId,
                packageName = subject.packageName,
                capturedAtEpochMs = capturedAtEpochMs,
            )
        ) {
            is EnterpriseAccessibilityObservationResult.Published ->
                captured.snapshot.takeIf { it.subject == subject }
            else -> null
        }
    }

    private fun captureWindow(
        window: AccessibilityWindowInfo,
        taskId: String,
        packageName: String,
        capturedAtEpochMs: Long,
    ): EnterpriseAccessibilityObservationResult = when (
        val result = observer.capture(
            window = window,
            request = AndroidAccessibilityReadRequest(
                taskId = taskId,
                capturedAtEpochMs = capturedAtEpochMs,
                privacyPolicyVersion = PRIVACY_POLICY_VERSION,
                expectedPackageName = packageName,
            ),
        )
    ) {
        is AndroidAccessibilityObservationResult.Published -> {
            latestSnapshot = result.snapshot
            EnterpriseAccessibilityObservationResult.Published(result.snapshot)
        }
        is AndroidAccessibilityObservationResult.Absent ->
            EnterpriseAccessibilityObservationResult.Unavailable("observer-${canonical(result.reason.name)}")
        is AndroidAccessibilityObservationResult.ReadRejected ->
            EnterpriseAccessibilityObservationResult.Rejected("reader-${canonical(result.reason.name)}")
        is AndroidAccessibilityObservationResult.SnapshotRejected ->
            EnterpriseAccessibilityObservationResult.Rejected("snapshot-${canonical(result.reason.name)}")
    }

    private fun matchingWindows(packageName: String): List<AccessibilityWindowInfo> =
        service.windows.filter { window -> windowPackageName(window) == packageName }

    private fun exactWindow(subject: DeviceSubjectRef): AccessibilityWindowInfo? {
        val matches = service.windows.filter { window ->
            "window-${window.id}" == subject.windowId &&
                "display-${displayId(window)}" == subject.displayId &&
                windowPackageName(window) == subject.packageName
        }
        return matches.singleOrNull()
    }

    private fun windowPackageName(window: AccessibilityWindowInfo): String? {
        val root = window.root ?: return null
        return try {
            root.packageName?.toString()?.trim()
        } finally {
            releaseNode(root)
        }
    }

    private fun findNativeMatches(
        window: AccessibilityWindowInfo,
        subject: DeviceSubjectRef,
        taskId: String,
        targetFingerprint: String,
    ): List<NativeMatch> {
        val root = window.root ?: return emptyList()
        val output = mutableListOf<NativeMatch>()
        try {
            scanNativeNode(
                node = root,
                localId = "node-0",
                parentLocalId = null,
                parentFingerprint = null,
                subject = subject,
                taskId = taskId,
                targetFingerprint = targetFingerprint,
                output = output,
            )
        } finally {
            releaseNode(root)
        }
        return output
    }

    private fun scanNativeNode(
        node: AccessibilityNodeInfo,
        localId: String,
        parentLocalId: String?,
        parentFingerprint: String?,
        subject: DeviceSubjectRef,
        taskId: String,
        targetFingerprint: String,
        output: MutableList<NativeMatch>,
    ) {
        if (output.size > 1) return
        val role = role(node.className?.toString())
        val fingerprint = sha256(
            listOf(
                subject.packageName,
                subject.windowId,
                subject.displayId,
                taskId,
                subject.snapshotVersion.toString(),
                localId,
                parentLocalId.orEmpty(),
                role,
            ).joinToString("\u001f"),
        )
        if (fingerprint == targetFingerprint) {
            nativeMatch(node, fingerprint, parentFingerprint, role)?.let(output::add)
        }
        for (index in 0 until node.childCount) {
            if (output.size > 1) return
            val child = node.getChild(index) ?: continue
            try {
                scanNativeNode(
                    node = child,
                    localId = "$localId-$index",
                    parentLocalId = localId,
                    parentFingerprint = fingerprint,
                    subject = subject,
                    taskId = taskId,
                    targetFingerprint = targetFingerprint,
                    output = output,
                )
            } finally {
                releaseNode(child)
            }
        }
    }

    private fun nativeMatch(
        node: AccessibilityNodeInfo,
        fingerprint: String,
        parentFingerprint: String?,
        role: String,
    ): NativeMatch? {
        val sensitivity = classifier.classify(
            AccessibilityNodeSensitivityMetadata(
                password = node.isPassword,
                editable = node.isEditable,
                className = node.className?.toString(),
                viewIdResourceName = runCatching { node.viewIdResourceName }.getOrNull(),
            ),
        )
        val rawName = runCatching { (node.contentDescription ?: node.text)?.toString().orEmpty() }
            .getOrDefault("")
        val sanitized = runCatching { privacyPolicy.sanitize(rawName, sensitivity) }.getOrNull()
            ?: return null
        val structuralDigest = sha256(
            listOf(
                fingerprint,
                parentFingerprint.orEmpty(),
                role,
                node.isVisibleToUser.toString(),
                node.isEnabled.toString(),
                node.isEditable.toString(),
                sanitized.privacyClass.name,
            ).joinToString("\u001f"),
        )
        @Suppress("DEPRECATION")
        val copy = AccessibilityNodeInfo.obtain(node)
        return NativeMatch(
            node = copy,
            structuralDigestSha256 = structuralDigest,
            accessibleNameDigestSha256 = sha256(sanitized.value),
            privacyClass = sanitized.privacyClass,
        )
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun canonical(value: String): String = value.lowercase().replace('_', '-')

    private data class ExecutionBinding(
        val token: String,
        val taskId: String,
        val subject: DeviceSubjectRef,
        val targetFingerprint: String,
        val structuralDigestSha256: String,
        val accessibleNameDigestSha256: String,
        val privacyClass: DeviceUiPrivacyClass,
        val issuedAtEpochMs: Long,
        val expiresAtEpochMs: Long,
    )

    private data class NativeMatch(
        val node: AccessibilityNodeInfo,
        val structuralDigestSha256: String,
        val accessibleNameDigestSha256: String,
        val privacyClass: DeviceUiPrivacyClass,
    )

    private companion object {
        const val PRIVACY_POLICY_VERSION = "enterprise-accessibility-v1"
    }
}
