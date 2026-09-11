package dev.ed3c.autowebview.executor.webview

import dev.ed3c.autowebview.executor.BrowserActionKind
import dev.ed3c.autowebview.executor.BrowserActionPayload
import dev.ed3c.autowebview.executor.FillTextPayload
import dev.ed3c.autowebview.executor.SelectOptionPayload
import java.net.URI
import java.security.MessageDigest

data class PlaySafeWebViewPolicy(
    val allowedOrigins: Set<String>,
    val clickNavigationExpectations: Map<String, String> = emptyMap(),
) {
    val normalizedOrigins: Set<String> = allowedOrigins.map(::normalizeHttpsOrigin).toSet()
    private val normalizedClickNavigationExpectations: Map<String, String> =
        clickNavigationExpectations.mapValues { (_, value) -> canonicalHttpsUrl(value) }

    init {
        require(allowedOrigins.isNotEmpty()) { "Play-safe WebView requires an explicit owned-origin allowlist" }
        require(allowedOrigins.size <= 16) { "Play-safe WebView origin allowlist is unbounded" }
        require(clickNavigationExpectations.size <= 256) { "Play-safe click expectation map is unbounded" }
        clickNavigationExpectations.forEach { (fingerprint, expectedUrl) ->
            require(fingerprint.matches(Regex("[0-9a-f]{64}"))) {
                "Play-safe click expectation requires an exact SHA-256 target fingerprint"
            }
            require(normalizeHttpsOrigin(expectedUrl) in normalizedOrigins) {
                "Play-safe click expectation must remain inside the owned-origin allowlist"
            }
        }
    }

    fun admits(pageUrl: String): Boolean = runCatching {
        normalizeHttpsOrigin(pageUrl) in normalizedOrigins
    }.getOrDefault(false)

    fun expectedClickNavigation(targetFingerprint: String): String? =
        normalizedClickNavigationExpectations[targetFingerprint]
}

data class PlaySafeWebElementObservation(
    val pageUrl: String,
    val pageNonce: String,
    val localId: String,
    val token: String,
    val tag: String,
    val role: String?,
    val accessibleName: String,
    val inputType: String?,
    val visible: Boolean,
    val enabled: Boolean,
    val editable: Boolean,
    val sensitivity: String,
    val documentDigestSha256: String,
    val valueDigestSha256: String?,
    val inputEventCount: Int,
    val changeEventCount: Int,
) {
    val fingerprint: String = playSafeFingerprint(
        pageUrl = pageUrl,
        pageNonce = pageNonce,
        localId = localId,
        tag = tag,
        role = role,
        accessibleName = accessibleName,
        inputType = inputType,
    )
}

enum class PlaySafePostconditionVerdict {
    VERIFIED_APPLIED,
    INCONCLUSIVE,
}

object PlaySafeWebPostconditionVerifier {
    fun verify(
        kind: BrowserActionKind,
        payload: BrowserActionPayload,
        expectedFingerprint: String,
        pre: PlaySafeWebElementObservation,
        post: PlaySafeWebElementObservation?,
        currentPageUrl: String,
        expectedClickNavigationUrl: String?,
    ): PlaySafePostconditionVerdict {
        if (kind == BrowserActionKind.CLICK) {
            val expected = expectedClickNavigationUrl ?: return PlaySafePostconditionVerdict.INCONCLUSIVE
            val current = runCatching { canonicalHttpsUrl(currentPageUrl) }.getOrNull()
                ?: return PlaySafePostconditionVerdict.INCONCLUSIVE
            val before = runCatching { canonicalHttpsUrl(pre.pageUrl) }.getOrNull()
                ?: return PlaySafePostconditionVerdict.INCONCLUSIVE
            return if (current == expected && current != before) {
                PlaySafePostconditionVerdict.VERIFIED_APPLIED
            } else {
                PlaySafePostconditionVerdict.INCONCLUSIVE
            }
        }

        val exact = post ?: return PlaySafePostconditionVerdict.INCONCLUSIVE
        if (exact.fingerprint != expectedFingerprint) return PlaySafePostconditionVerdict.INCONCLUSIVE
        if (exact.pageUrl != pre.pageUrl || exact.pageNonce != pre.pageNonce) {
            return PlaySafePostconditionVerdict.INCONCLUSIVE
        }
        return when (kind) {
            BrowserActionKind.CLICK -> PlaySafePostconditionVerdict.INCONCLUSIVE
            BrowserActionKind.FILL_TEXT -> {
                val value = (payload as? FillTextPayload)?.value
                    ?: return PlaySafePostconditionVerdict.INCONCLUSIVE
                val expectedDigest = sha256(value)
                if (
                    pre.valueDigestSha256 != expectedDigest &&
                    exact.valueDigestSha256 == expectedDigest &&
                    exact.inputEventCount > pre.inputEventCount &&
                    exact.changeEventCount > pre.changeEventCount
                ) {
                    PlaySafePostconditionVerdict.VERIFIED_APPLIED
                } else {
                    PlaySafePostconditionVerdict.INCONCLUSIVE
                }
            }

            BrowserActionKind.SELECT_OPTION -> {
                val value = (payload as? SelectOptionPayload)?.value
                    ?: return PlaySafePostconditionVerdict.INCONCLUSIVE
                val expectedDigest = sha256(value)
                if (
                    pre.valueDigestSha256 != expectedDigest &&
                    exact.valueDigestSha256 == expectedDigest &&
                    exact.changeEventCount > pre.changeEventCount
                ) {
                    PlaySafePostconditionVerdict.VERIFIED_APPLIED
                } else {
                    PlaySafePostconditionVerdict.INCONCLUSIVE
                }
            }
        }
    }
}

internal fun normalizeHttpsOrigin(value: String): String {
    val uri = URI(value)
    require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS owned origins are executable" }
    require(uri.host != null && uri.host.isNotBlank()) { "Executable WebView URL requires an exact host" }
    require(uri.userInfo == null) { "Executable WebView origin cannot contain user info" }
    val host = uri.host.lowercase()
    val port = uri.port
    require(port == -1 || port in 1..65535) { "Executable WebView origin has invalid port" }
    return if (port == -1 || port == 443) "https://$host" else "https://$host:$port"
}

internal fun canonicalHttpsUrl(value: String): String {
    val uri = URI(value)
    require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS owned URLs are executable" }
    require(uri.host != null && uri.host.isNotBlank()) { "Executable WebView URL requires an exact host" }
    require(uri.userInfo == null) { "Executable WebView URL cannot contain user info" }
    require(uri.port == -1 || uri.port in 1..65535) { "Executable WebView URL has invalid port" }
    val effectivePort = if (uri.port == 443) -1 else uri.port
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    return URI(
        "https",
        null,
        uri.host.lowercase(),
        effectivePort,
        path,
        uri.rawQuery,
        uri.rawFragment,
    ).normalize().toASCIIString()
}

internal fun playSafeFingerprint(
    pageUrl: String,
    pageNonce: String,
    localId: String,
    tag: String,
    role: String?,
    accessibleName: String,
    inputType: String?,
): String = sha256(
    listOf(
        pageUrl,
        pageNonce,
        localId,
        tag.lowercase(),
        role.orEmpty().lowercase(),
        accessibleName,
        inputType.orEmpty().lowercase(),
    ).joinToString("\u001f"),
)

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

internal val PLAY_SAFE_FIXED_BRIDGE_JS: String = """
    (function () {
      'use strict';
      if (window.__kawBridgeInstalledV1 === true) return;
      Object.defineProperty(window, '__kawBridgeInstalledV1', { value: true, writable: false });
      const MAX_ELEMENTS = 2048;
      const MAX_DOM_CHARS = 262144;
      const MAX_EVENT_COUNT = 1000000;
      const tokens = new Map();
      const eventCounters = new WeakMap();
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
      function eventState(element) {
        let state = eventCounters.get(element);
        if (!state) {
          state = { input: 0, change: 0 };
          eventCounters.set(element, state);
        }
        return state;
      }
      function recordEvent(kind, event) {
        const target = event.target;
        if (!(target instanceof Element)) return;
        const state = eventState(target);
        state[kind] = Math.min(MAX_EVENT_COUNT, state[kind] + 1);
      }
      document.addEventListener('input', function (event) { recordEvent('input', event); }, true);
      document.addEventListener('change', function (event) { recordEvent('change', event); }, true);
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
        const events = eventState(element);
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
          valueDigestSha256: valueDigest,
          inputEventCount: events.input,
          changeEventCount: events.change
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
            const expectedNavigationUrl = String(request.expectedNavigationUrl || '');
            if (!expectedNavigationUrl) return { requestId: requestId, status: 'rejected', code: 'click-postcondition-not-declared' };
            if (element.tagName.toLowerCase() !== 'a') return { requestId: requestId, status: 'rejected', code: 'click-navigation-target-invalid' };
            let actualDestination;
            let expectedDestination;
            try {
              actualDestination = new URL(element.getAttribute('href') || '', location.href);
              expectedDestination = new URL(expectedNavigationUrl);
            } catch (error) {
              return { requestId: requestId, status: 'rejected', code: 'click-navigation-url-invalid' };
            }
            if (actualDestination.protocol !== 'https:' || expectedDestination.protocol !== 'https:' || actualDestination.href !== expectedDestination.href) {
              return { requestId: requestId, status: 'rejected', code: 'click-navigation-destination-mismatch' };
            }
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
