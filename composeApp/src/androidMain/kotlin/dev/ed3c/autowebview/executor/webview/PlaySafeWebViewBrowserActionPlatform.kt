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
import dev.ed3c.autowebview.executor.BrowserTargetQuery
import dev.ed3c.autowebview.executor.BrowserTargetSensitivity
import dev.ed3c.autowebview.executor.ClickPayload
import dev.ed3c.autowebview.executor.FillTextPayload
import dev.ed3c.autowebview.executor.PlatformBrowserActionResult
import dev.ed3c.autowebview.executor.ResolvedBrowserTarget
import dev.ed3c.autowebview.executor.SelectOptionPayload
import dev.ed3c.autowebview.executor.BrowserSideEffectState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class PlaySafeWebViewPageObservation(
    val pageUrl: String,
    val capturedAtEpochMs: Long,
    val interactiveElements: List<InteractiveElement>,
)

/**
 * BrowserActionPlatform for an app-owned Play-safe WebView.
 *
 * All executable JavaScript is the fixed repository-owned bridge below. Action payloads, target
 * identity and expected fields cross into the page only as WebMessage data. No model/network text
 * is concatenated into JavaScript, a selector, URL, coordinate or native call.
 */
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

        bindings.clear()
        return capture.elements.filter { element ->
            element.fingerprint == query.fingerprint &&
                (query.expectedRole == null || query.expectedRole.equals(element.role, ignoreCase = true)) &&
                (query.expectedAccessibleName == null || query.expectedAccessibleName.trim() == element.accessibleName.trim())
        }.map { element ->
            bindings[element.token] = TargetBinding.from(element)
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
        if (currentUrl() != command.pageUrl) return rejected("page-url-changed")
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
        if (cancellationSignal.isCancellationRequested()) {
            return PlatformBrowserActionResult.CancelledBeforeSideEffect
        }

        val actionResponse = try {
            bridgeRequest(actionRequest(command, binding))
        } catch (_: Throwable) {
            val navigated = command.kind == BrowserActionKind.CLICK && currentUrl() != command.pageUrl
            return if (navigated) {
                PlatformBrowserActionResult.Completed
            } else {
                failed("action-transport-unknown", BrowserSideEffectState.UNKNOWN)
            }
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
            val current = currentUrl()
            val pageChanged = current != command.pageUrl
            if (command.kind == BrowserActionKind.CLICK && pageChanged) {
                return PlatformBrowserActionResult.Completed
            }
            val post = runCatching { probe(binding) }.getOrNull()
            val verdict = PlaySafeWebPostconditionVerifier.verify(
                kind = command.kind,
                payload = command.payload,
                expectedFingerprint = command.targetFingerprint,
                preDocumentDigestSha256 = pre.documentDigestSha256,
                post = post,
                pageUrlChanged = pageChanged,
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
                webView.evaluateJavascript(FIXED_BOOTSTRAP_JS) {
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
                        val origin = Uri.parse(normalizeHttpsOrigin(pageUrl))
                        webView.postWebMessage(
                            WebMessage(PORT_BIND_MESSAGE, arrayOf(ports[1])),
                            origin,
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
    ) {
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
            fun from(observation: PlaySafeWebElementObservation) = TargetBinding(
                token = observation.token,
                pageUrl = observation.pageUrl,
                pageNonce = observation.pageNonce,
                localId = observation.localId,
                fingerprint = observation.fingerprint,
                tag = observation.tag,
                role = observation.role,
                accessibleName = observation.accessibleName,
                inputType = observation.inputType,
            )
        }
    }

    private companion object {
        const val MAX_ELEMENTS = 2_048
        const val BRIDGE_TIMEOUT_MS = 1_500L
        const val POSTCONDITION_ATTEMPTS = 10
        const val POSTCONDITION_POLL_MS = 50L
        const val PORT_BIND_MESSAGE = "KAW_PORT_V1"

        val FIXED_BOOTSTRAP_JS: String = """
            (function () {
              'use strict';
              if (window.__kawBridgeInstalledV1 === true) return;
              Object.defineProperty(window, '__kawBridgeInstalledV1', { value: true, writable: false });
              const MAX_ELEMENTS = 2048;
              const MAX_DOM_CHARS = 262144;
              const tokens = new Map();
              function randomToken() {
                const bytes = new Uint8Array(16);
                crypto.getRandomValues(bytes);
                return Array.from(bytes).map(function (b) { return b.toString(16).padStart(2, '0'); }).join('');
              }
              const pageNonce = randomToken();
              async function sha256(value) {
                const data = new TextEncoder().encode(value);
                const digest = await crypto.subtle.digest('SHA-256', data);
                return Array.from(new Uint8Array(digest)).map(function (b) { return b.toString(16).padStart(2, '0'); }).join('');
              }
              function candidates() {
                const fixedSelector = 'button,input,textarea,select,a,[role],[tabindex],[contenteditable="true"]';
                return Array.from(document.querySelectorAll(fixedSelector)).slice(0, MAX_ELEMENTS);
              }
              function visible(element) {
                const rect = element.getBoundingClientRect();
                const style = window.getComputedStyle(element);
                return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
              }
              function role(element) {
                const explicit = (element.getAttribute('role') || '').trim();
                if (explicit) return explicit.slice(0, 64);
                const tag = element.tagName.toLowerCase();
                if (tag === 'button') return 'button';
                if (tag === 'a') return 'link';
                if (tag === 'select') return 'select';
                if (tag === 'textarea') return 'textbox';
                if (tag === 'input') return 'textbox';
                return null;
              }
              function sensitivity(element) {
                const type = (element.getAttribute('type') || '').toLowerCase();
                const autocomplete = (element.getAttribute('autocomplete') || '').toLowerCase();
                const metadata = [
                  element.getAttribute('name') || '',
                  element.getAttribute('id') || '',
                  autocomplete,
                  type
                ].join(' ').toLowerCase();
                if (type === 'password') return 'PASSWORD';
                if (type === 'file') return 'SECRET';
                if (autocomplete.indexOf('cc-') >= 0 || /payment|credit|debit|card|cvv|cvc/.test(metadata)) return 'PAYMENT';
                if (/secret|token|api-key|private-key|otp|one-time|verification-code/.test(metadata)) return 'SECRET';
                return 'NONE';
              }
              function accessibleName(element, sensitive) {
                if (sensitive !== 'NONE') return '';
                const raw = (
                  element.getAttribute('aria-label') ||
                  element.getAttribute('name') ||
                  element.getAttribute('placeholder') ||
                  element.innerText ||
                  ''
                );
                return String(raw).replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 256);
              }
              async function documentDigest() {
                const html = String(document.documentElement ? document.documentElement.outerHTML : '').slice(0, MAX_DOM_CHARS);
                return sha256(html);
              }
              async function record(element, index, token) {
                const tag = element.tagName.toLowerCase();
                const sensitive = sensitivity(element);
                const editable = tag === 'input' || tag === 'textarea' || tag === 'select';
                let valueDigest = null;
                if (editable && sensitive === 'NONE') valueDigest = await sha256(String(element.value || ''));
                return {
                  token: token,
                  localId: 'interactive-' + String(index),
                  tag: tag.slice(0, 32),
                  role: role(element),
                  accessibleName: accessibleName(element, sensitive),
                  inputType: tag === 'input' ? String(element.getAttribute('type') || 'text').toLowerCase().slice(0, 64) : null,
                  visible: visible(element),
                  enabled: !element.disabled,
                  editable: editable,
                  sensitivity: sensitive,
                  valueDigestSha256: valueDigest
                };
              }
              async function capture() {
                tokens.clear();
                const list = candidates();
                const output = [];
                for (let index = 0; index < list.length; index += 1) {
                  const token = randomToken();
                  tokens.set(token, list[index]);
                  output.push(await record(list[index], index, token));
                }
                return output;
              }
              async function probe(token) {
                const element = tokens.get(token);
                if (!element || !element.isConnected) return null;
                const list = candidates();
                const index = list.indexOf(element);
                if (index < 0) return null;
                return record(element, index, token);
              }
              function sameNullable(actual, expected) {
                return (actual === null ? null : String(actual)) === (expected === null ? null : String(expected));
              }
              async function handle(request) {
                const requestId = String(request.requestId || '');
                if (request.type === 'capture') {
                  if (String(request.expectedPageUrl || '') !== location.href) {
                    return { requestId: requestId, status: 'rejected', code: 'page-url-mismatch' };
                  }
                  return {
                    requestId: requestId,
                    status: 'ok',
                    pageUrl: location.href,
                    pageNonce: pageNonce,
                    documentDigestSha256: await documentDigest(),
                    elements: await capture()
                  };
                }
                if (request.type === 'probe') {
                  const item = await probe(String(request.token || ''));
                  if (!item) return { requestId: requestId, status: 'rejected', code: 'target-stale' };
                  return {
                    requestId: requestId,
                    status: 'ok',
                    pageUrl: location.href,
                    pageNonce: pageNonce,
                    documentDigestSha256: await documentDigest(),
                    element: item
                  };
                }
                if (request.type === 'action') {
                  const token = String(request.token || '');
                  const element = tokens.get(token);
                  if (!element || !element.isConnected) return { requestId: requestId, status: 'rejected', code: 'target-stale' };
                  const list = candidates();
                  const index = list.indexOf(element);
                  if (index < 0) return { requestId: requestId, status: 'rejected', code: 'target-stale' };
                  const current = await record(element, index, token);
                  const expected = request.expected || {};
                  if (
                    current.localId !== expected.localId ||
                    current.tag !== expected.tag ||
                    !sameNullable(current.role, expected.role) ||
                    current.accessibleName !== expected.accessibleName ||
                    !sameNullable(current.inputType, expected.inputType)
                  ) return { requestId: requestId, status: 'rejected', code: 'target-revalidation-failed' };
                  if (!current.visible || !current.enabled || current.sensitivity !== 'NONE') {
                    return { requestId: requestId, status: 'rejected', code: 'target-not-executable' };
                  }
                  const kind = String(request.kind || '');
                  if (kind === 'CLICK') {
                    if (typeof element.click !== 'function') return { requestId: requestId, status: 'rejected', code: 'click-unsupported' };
                    element.click();
                    return { requestId: requestId, status: 'accepted' };
                  }
                  if (kind === 'FILL_TEXT') {
                    const value = request.value;
                    if (typeof value !== 'string' || value.length > 2048) return { requestId: requestId, status: 'rejected', code: 'fill-value-invalid' };
                    const tag = element.tagName.toLowerCase();
                    if (tag !== 'input' && tag !== 'textarea') return { requestId: requestId, status: 'rejected', code: 'fill-target-invalid' };
                    const prototype = tag === 'input' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
                    const descriptor = Object.getOwnPropertyDescriptor(prototype, 'value');
                    if (!descriptor || typeof descriptor.set !== 'function') return { requestId: requestId, status: 'rejected', code: 'fill-setter-unavailable' };
                    descriptor.set.call(element, value);
                    element.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                    element.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                    return { requestId: requestId, status: 'accepted' };
                  }
                  if (kind === 'SELECT_OPTION') {
                    const value = request.value;
                    if (typeof value !== 'string' || value.length > 512 || element.tagName.toLowerCase() !== 'select') {
                      return { requestId: requestId, status: 'rejected', code: 'select-target-invalid' };
                    }
                    const option = Array.from(element.options).filter(function (item) { return item.value === value; });
                    if (option.length !== 1) return { requestId: requestId, status: 'rejected', code: 'select-option-not-exact' };
                    element.value = value;
                    element.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                    element.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                    return { requestId: requestId, status: 'accepted' };
                  }
                  return { requestId: requestId, status: 'rejected', code: 'action-kind-unsupported' };
                }
                return { requestId: requestId, status: 'rejected', code: 'request-type-unsupported' };
              }
              window.addEventListener('message', function (event) {
                if (event.data !== 'KAW_PORT_V1' || !event.ports || event.ports.length !== 1) return;
                const port = event.ports[0];
                port.onmessage = function (message) {
                  let request;
                  try { request = JSON.parse(String(message.data || '')); }
                  catch (error) { port.postMessage(JSON.stringify({ status: 'error', code: 'invalid-json' })); return; }
                  Promise.resolve(handle(request)).then(function (response) {
                    port.postMessage(JSON.stringify(response));
                  }).catch(function () {
                    port.postMessage(JSON.stringify({ requestId: String(request.requestId || ''), status: 'error', code: 'bridge-exception' }));
                  });
                };
                port.start();
                port.postMessage(JSON.stringify({ type: 'ready' }));
              }, false);
            })();
        """.trimIndent()
    }
}
