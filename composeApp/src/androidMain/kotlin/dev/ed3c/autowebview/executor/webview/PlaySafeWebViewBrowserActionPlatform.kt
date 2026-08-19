package dev.ed3c.autowebview.executor.webview

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebView
import dev.ed3c.autowebview.device.policy.DistributionProfile
import dev.ed3c.autowebview.device.profile.AndroidCompiledDistributionProfile
import dev.ed3c.autowebview.domain.InteractiveElement
import dev.ed3c.autowebview.executor.BrowserActionCancellationSignal
import dev.ed3c.autowebview.executor.BrowserActionCommand
import dev.ed3c.autowebview.executor.BrowserActionKind
import dev.ed3c.autowebview.executor.BrowserActionPlatform
import dev.ed3c.autowebview.executor.BrowserSideEffectState
import dev.ed3c.autowebview.executor.BrowserTargetQuery
import dev.ed3c.autowebview.executor.BrowserTargetSensitivity
import dev.ed3c.autowebview.executor.ClickPayload
import dev.ed3c.autowebview.executor.FillTextPayload
import dev.ed3c.autowebview.executor.PlatformBrowserActionResult
import dev.ed3c.autowebview.executor.ResolvedBrowserTarget
import dev.ed3c.autowebview.executor.SelectOptionPayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class PlaySafeWebViewPageObservation(
    val pageUrl: String,
    val capturedAtEpochMs: Long,
    val interactiveElements: List<InteractiveElement>,
)

class PlaySafeWebViewBrowserActionPlatform(
    private val webView: WebView,
    private val policy: PlaySafeWebViewPolicy,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : BrowserActionPlatform {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindings = ConcurrentHashMap<String, TargetBinding>()

    suspend fun captureOwnedPage(): PlaySafeWebViewPageObservation {
        require(playSafeProfileActive()) { "Play-safe WebView adapter requires the PLAY_SAFE compiled profile" }
        val pageUrl = currentUrl() ?: error("WebView has no current page")
        require(policy.admits(pageUrl)) { "Current WebView origin is outside the owned Play-safe allowlist" }
        val capture = captureBridge(pageUrl)
        return PlaySafeWebViewPageObservation(
            pageUrl = pageUrl,
            capturedAtEpochMs = nowEpochMs(),
            interactiveElements = capture.elements.map { element ->
                InteractiveElement(
                    fingerprint = element.fingerprint,
                    tag = element.tag,
                    role = element.role,
                    text = "",
                    accessibleName = element.accessibleName,
                    inputType = element.inputType,
                )
            },
        )
    }

    override suspend fun resolve(query: BrowserTargetQuery): List<ResolvedBrowserTarget> {
        if (!playSafeProfileActive()) return emptyList()
        val currentUrl = currentUrl() ?: return emptyList()
        if (currentUrl != query.pageUrl || !policy.admits(currentUrl)) return emptyList()
        val capture = runCatching { captureBridge(currentUrl) }.getOrNull() ?: return emptyList()
        if (capture.pageUrl != query.pageUrl) return emptyList()

        val issuedAt = nowEpochMs()
        bindings.clear()
        return capture.elements.filter { element ->
            element.fingerprint == query.fingerprint &&
                (query.expectedRole == null || query.expectedRole.equals(element.role, ignoreCase = true)) &&
                (query.expectedAccessibleName == null || query.expectedAccessibleName.trim() == element.accessibleName.trim())
        }.map { element ->
            val binding = TargetBinding.from(element, issuedAt)
            bindings[element.token] = binding
            ResolvedBrowserTarget(
                executionToken = element.token,
                pageUrl = element.pageUrl,
                fingerprint = element.fingerprint,
                role = element.role,
                accessibleName = element.accessibleName,
                tag = element.tag,
                inputType = element.inputType,
                visible = element.visible,
                enabled = element.enabled,
                editable = element.editable,
                sensitivity = sensitivity(element.sensitivity),
            )
        }
    }

    override suspend fun perform(
        command: BrowserActionCommand,
        cancellationSignal: BrowserActionCancellationSignal,
    ): PlatformBrowserActionResult {
        if (!playSafeProfileActive()) return rejected("profile-not-play-safe")
        if (!policy.admits(command.pageUrl)) return rejected("origin-not-owned")
        val binding = bindings[command.targetExecutionToken] ?: return rejected("target-token-absent")
        if (binding.pageUrl != command.pageUrl || binding.fingerprint != command.targetFingerprint) {
            return rejected("target-binding-mismatch")
        }
        if (binding.expired(nowEpochMs())) return rejected("target-token-expired")
        if (currentUrl() != command.pageUrl) return rejected("page-url-changed")

        val expectedClickNavigationUrl = when (command.kind) {
            BrowserActionKind.CLICK -> policy.expectedClickNavigation(command.targetFingerprint)
                ?: return rejected("click-postcondition-not-declared")
            else -> null
        }
        if (
            expectedClickNavigationUrl != null &&
            canonicalHttpsUrl(expectedClickNavigationUrl) == canonicalHttpsUrl(command.pageUrl)
        ) {
            return rejected("click-navigation-already-satisfied")
        }
        if (cancellationSignal.isCancellationRequested()) {
            return PlatformBrowserActionResult.CancelledBeforeSideEffect
        }

        val pre = runCatching { probe(binding) }.getOrElse {
            return failed("precondition-unavailable", BrowserSideEffectState.NONE)
        }
        if (!binding.matches(pre)) return rejected("target-revalidation-failed")
        if (!pre.visible || !pre.enabled || pre.sensitivity != "NONE") {
            return rejected("target-not-executable")
        }
        if (command.kind in setOf(BrowserActionKind.FILL_TEXT, BrowserActionKind.SELECT_OPTION) && !pre.editable) {
            return rejected("target-not-editable")
        }
        val requestedValueDigest = when (val payload = command.payload) {
            is FillTextPayload -> sha256(payload.value)
            is SelectOptionPayload -> sha256(payload.value)
            ClickPayload -> null
        }
        if (requestedValueDigest != null && pre.valueDigestSha256 == requestedValueDigest) {
            return rejected("postcondition-already-satisfied")
        }
        if (binding.expired(nowEpochMs())) return rejected("target-token-expired")
        if (cancellationSignal.isCancellationRequested()) {
            return PlatformBrowserActionResult.CancelledBeforeSideEffect
        }

        val actionResponse = try {
            bridgeRequest(actionRequest(command, binding))
        } catch (_: Throwable) {
            val current = currentUrl()
            if (
                command.kind == BrowserActionKind.CLICK &&
                current != null &&
                expectedClickNavigationUrl != null &&
                runCatching { canonicalHttpsUrl(current) == canonicalHttpsUrl(expectedClickNavigationUrl) }
                    .getOrDefault(false)
            ) {
                return PlatformBrowserActionResult.Completed
            }
            return failed("action-transport-unknown", BrowserSideEffectState.UNKNOWN)
        }
        when (actionResponse.optString("status")) {
            "rejected" -> return rejected(canonicalCode(actionResponse.optString("code", "bridge-rejected")))
            "accepted" -> Unit
            else -> return failed("action-bridge-error", BrowserSideEffectState.UNKNOWN)
        }

        if (cancellationSignal.isCancellationRequested()) {
            return failed("user-preempted-after-dispatch", BrowserSideEffectState.UNKNOWN)
        }

        repeat(POSTCONDITION_ATTEMPTS) {
            val current = currentUrl() ?: return failed("page-unavailable-after-dispatch", BrowserSideEffectState.UNKNOWN)
            val post = if (command.kind == BrowserActionKind.CLICK || current != command.pageUrl) {
                null
            } else {
                runCatching { probe(binding) }.getOrNull()
            }
            val verdict = PlaySafeWebPostconditionVerifier.verify(
                kind = command.kind,
                payload = command.payload,
                expectedFingerprint = command.targetFingerprint,
                pre = pre,
                post = post,
                currentPageUrl = current,
                expectedClickNavigationUrl = expectedClickNavigationUrl,
            )
            if (verdict == PlaySafePostconditionVerdict.VERIFIED_APPLIED) {
                return PlatformBrowserActionResult.Completed
            }
            if (cancellationSignal.isCancellationRequested()) {
                return failed("user-preempted-after-dispatch", BrowserSideEffectState.UNKNOWN)
            }
            delay(POSTCONDITION_POLL_MS)
        }
        return failed("postcondition-inconclusive", BrowserSideEffectState.UNKNOWN)
    }

    private suspend fun captureBridge(expectedPageUrl: String): BridgeCapture {
        val response = bridgeRequest(
            JSONObject()
                .put("type", "capture")
                .put("expectedPageUrl", expectedPageUrl),
        )
        require(response.optString("status") == "ok") { "Bridge capture rejected" }
        val pageUrl = response.getString("pageUrl")
        require(pageUrl == expectedPageUrl) { "Bridge page changed during capture" }
        val nonce = boundedToken(response.getString("pageNonce"), "page nonce")
        val documentDigest = digest(response.getString("documentDigestSha256"), "document digest")
        val array = response.getJSONArray("elements")
        require(array.length() <= MAX_ELEMENTS) { "Bridge returned too many interactive elements" }
        val elements = buildList {
            for (index in 0 until array.length()) {
                add(elementFromJson(pageUrl, nonce, documentDigest, array.getJSONObject(index)))
            }
        }
        return BridgeCapture(pageUrl, nonce, documentDigest, elements)
    }

    private suspend fun probe(binding: TargetBinding): PlaySafeWebElementObservation {
        val response = bridgeRequest(
            JSONObject()
                .put("type", "probe")
                .put("token", binding.token),
        )
        require(response.optString("status") == "ok") { "Bridge probe rejected" }
        val pageUrl = response.getString("pageUrl")
        val nonce = boundedToken(response.getString("pageNonce"), "page nonce")
        val documentDigest = digest(response.getString("documentDigestSha256"), "document digest")
        return elementFromJson(pageUrl, nonce, documentDigest, response.getJSONObject("element"))
    }

    private fun actionRequest(command: BrowserActionCommand, binding: TargetBinding): JSONObject =
        JSONObject()
            .put("type", "action")
            .put("token", binding.token)
            .put("kind", command.kind.name)
            .put(
                "expected",
                JSONObject()
                    .put("localId", binding.localId)
                    .put("tag", binding.tag)
                    .put("role", binding.role ?: JSONObject.NULL)
                    .put("accessibleName", binding.accessibleName)
                    .put("inputType", binding.inputType ?: JSONObject.NULL),
            )
            .also { request ->
                when (val payload = command.payload) {
                    ClickPayload -> Unit
                    is FillTextPayload -> request.put("value", payload.value)
                    is SelectOptionPayload -> request.put("value", payload.value)
                }
            }

    private suspend fun bridgeRequest(requestWithoutId: JSONObject): JSONObject {
        val pageUrl = currentUrl() ?: error("WebView page is absent")
        require(policy.admits(pageUrl)) { "WebView origin is not admitted" }
        val request = JSONObject(requestWithoutId.toString())
        val requestId = UUID.randomUUID().toString()
        request.put("requestId", requestId)
        val result = CompletableDeferred<JSONObject>()
        var nativePort: WebMessagePort? = null

        runOnMain {
            try {
                if (webView.url != pageUrl) error("WebView page changed before bridge setup")
                webView.evaluateJavascript(PLAY_SAFE_FIXED_BRIDGE_JS) {
                    try {
                        if (webView.url != pageUrl) error("WebView page changed while installing bridge")
                        val ports = webView.createWebMessageChannel()
                        nativePort = ports[0]
                        ports[0].setWebMessageCallback(
                            object : WebMessagePort.WebMessageCallback() {
                                override fun onMessage(port: WebMessagePort, message: WebMessage?) {
                                    try {
                                        val text = message?.data ?: return
                                        val json = JSONObject(text)
                                        when {
                                            json.optString("type") == "ready" ->
                                                port.postMessage(WebMessage(request.toString()))
                                            json.optString("requestId") == requestId ->
                                                result.complete(json)
                                        }
                                    } catch (error: Throwable) {
                                        result.completeExceptionally(error)
                                    }
                                }
                            },
                            mainHandler,
                        )
                        webView.postWebMessage(
                            WebMessage(PORT_BIND_MESSAGE, arrayOf(ports[1])),
                            Uri.parse(normalizeHttpsOrigin(pageUrl)),
                        )
                    } catch (error: Throwable) {
                        result.completeExceptionally(error)
                    }
                }
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }

        return try {
            withTimeout(BRIDGE_TIMEOUT_MS) { result.await() }
        } finally {
            nativePort?.let { port -> runOnMain { runCatching { port.close() } } }
        }
    }

    private fun elementFromJson(
        pageUrl: String,
        pageNonce: String,
        documentDigest: String,
        json: JSONObject,
    ): PlaySafeWebElementObservation {
        val localId = boundedToken(json.getString("localId"), "local id")
        val token = boundedToken(json.getString("token"), "target token")
        val tag = boundedText(json.getString("tag"), 32, "tag").lowercase()
        val role = json.nullableString("role")?.let { boundedText(it, 64, "role") }
        val accessibleName = boundedText(json.optString("accessibleName", ""), 256, "accessible name")
        val inputType = json.nullableString("inputType")?.let { boundedText(it, 64, "input type") }
        val sensitivity = json.optString("sensitivity", "NONE")
        require(sensitivity in setOf("NONE", "PASSWORD", "PAYMENT", "SECRET", "CROSS_ORIGIN")) {
            "Bridge sensitivity is invalid"
        }
        val valueDigest = json.nullableString("valueDigestSha256")?.let { digest(it, "value digest") }
        return PlaySafeWebElementObservation(
            pageUrl = pageUrl,
            pageNonce = pageNonce,
            localId = localId,
            token = token,
            tag = tag,
            role = role,
            accessibleName = accessibleName,
            inputType = inputType,
            visible = json.getBoolean("visible"),
            enabled = json.getBoolean("enabled"),
            editable = json.getBoolean("editable"),
            sensitivity = sensitivity,
            documentDigestSha256 = documentDigest,
            valueDigestSha256 = valueDigest,
            inputEventCount = boundedEventCount(json.getInt("inputEventCount"), "input event count"),
            changeEventCount = boundedEventCount(json.getInt("changeEventCount"), "change event count"),
        )
    }

    private suspend fun currentUrl(): String? = mainValue { webView.url }

    private suspend fun <T> mainValue(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        runOnMain {
            try {
                if (continuation.isActive) continuation.resume(block())
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun playSafeProfileActive(): Boolean =
        AndroidCompiledDistributionProfile.current == DistributionProfile.PLAY_SAFE

    private fun sensitivity(value: String): BrowserTargetSensitivity = when (value) {
        "PASSWORD" -> BrowserTargetSensitivity.PASSWORD
        "PAYMENT" -> BrowserTargetSensitivity.PAYMENT
        "SECRET" -> BrowserTargetSensitivity.SECRET
        "CROSS_ORIGIN" -> BrowserTargetSensitivity.CROSS_ORIGIN
        else -> BrowserTargetSensitivity.NONE
    }

    private fun rejected(code: String): PlatformBrowserActionResult.Rejected =
        PlatformBrowserActionResult.Rejected(code = code, message = code.replace('-', ' '))

    private fun failed(code: String, effect: BrowserSideEffectState): PlatformBrowserActionResult.Failed =
        PlatformBrowserActionResult.Failed(
            code = code,
            message = code.replace('-', ' '),
            retryable = false,
            sideEffectState = effect,
        )

    private fun boundedToken(value: String, field: String): String {
        require(value.length in 1..256 && value.none(Char::isWhitespace) && value.none(Char::isISOControl)) {
            "$field is not a bounded opaque token"
        }
        require(value.none { it in "*?;&|`$<>\\\"'" }) { "$field contains executable metacharacters" }
        return value
    }

    private fun boundedText(value: String, max: Int, field: String): String {
        require(value.length <= max && value.none(Char::isISOControl)) { "$field is outside the bounded text contract" }
        return value
    }

    private fun boundedEventCount(value: Int, field: String): Int {
        require(value in 0..MAX_EVENT_COUNT) { "$field is outside the bounded counter contract" }
        return value
    }

    private fun digest(value: String, field: String): String {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "$field is not a SHA-256 digest" }
        return value
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun canonicalCode(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9._:-]+"), "-")
        .trim('-')
        .ifBlank { "bridge-rejected" }

    private data class BridgeCapture(
        val pageUrl: String,
        val pageNonce: String,
        val documentDigestSha256: String,
        val elements: List<PlaySafeWebElementObservation>,
    )

    private data class TargetBinding(
        val token: String,
        val pageUrl: String,
        val pageNonce: String,
        val localId: String,
        val fingerprint: String,
        val tag: String,
        val role: String?,
        val accessibleName: String,
        val inputType: String?,
        val issuedAtEpochMs: Long,
        val expiresAtEpochMs: Long,
    ) {
        fun expired(nowEpochMs: Long): Boolean =
            nowEpochMs < issuedAtEpochMs || nowEpochMs > expiresAtEpochMs

        fun matches(observation: PlaySafeWebElementObservation): Boolean =
            observation.token == token &&
                observation.pageUrl == pageUrl &&
                observation.pageNonce == pageNonce &&
                observation.localId == localId &&
                observation.fingerprint == fingerprint &&
                observation.tag == tag &&
                observation.role == role &&
                observation.accessibleName == accessibleName &&
                observation.inputType == inputType

        companion object {
            fun from(observation: PlaySafeWebElementObservation, issuedAtEpochMs: Long) = TargetBinding(
                token = observation.token,
                pageUrl = observation.pageUrl,
                pageNonce = observation.pageNonce,
                localId = observation.localId,
                fingerprint = observation.fingerprint,
                tag = observation.tag,
                role = observation.role,
                accessibleName = observation.accessibleName,
                inputType = observation.inputType,
                issuedAtEpochMs = issuedAtEpochMs,
                expiresAtEpochMs = issuedAtEpochMs + TARGET_TOKEN_TTL_MS,
            )
        }
    }

    private companion object {
        const val MAX_ELEMENTS = 2_048
        const val MAX_EVENT_COUNT = 1_000_000
        const val BRIDGE_TIMEOUT_MS = 1_500L
        const val TARGET_TOKEN_TTL_MS = 2_000L
        const val POSTCONDITION_ATTEMPTS = 10
        const val POSTCONDITION_POLL_MS = 50L
        const val PORT_BIND_MESSAGE = "KAW_PORT_V1"
    }
}
