package dev.ed3c.autowebview.executor

import dev.ed3c.autowebview.domain.PageContext
import dev.ed3c.autowebview.domain.StableIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun interface BrowserScriptEvaluator {
    suspend fun evaluate(script: String): String
}

/**
 * Executes only repository-owned JavaScript against the currently observed in-app WebView.
 * Caller values are serialized as JSON data and can never become selectors or JavaScript source.
 */
class CurrentWebViewBrowserActionPlatform(
    private val currentContext: () -> PageContext?,
    private val evaluator: BrowserScriptEvaluator,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : BrowserActionPlatform {
    private var tokenSequence = 0L
    private val bindings = linkedMapOf<String, TargetBinding>()

    override suspend fun resolve(query: BrowserTargetQuery): List<ResolvedBrowserTarget> {
        val page = currentContext() ?: return emptyList()
        if (page.url != query.pageUrl) return emptyList()
        val matches = page.interactiveElements.filter { element ->
            element.fingerprint == query.fingerprint &&
                (query.expectedRole == null || query.expectedRole.equals(element.role, ignoreCase = true)) &&
                (query.expectedAccessibleName == null ||
                    query.expectedAccessibleName.trim() == element.accessibleName.trim())
        }
        bindings.clear()
        return matches.map { element ->
            tokenSequence += 1
            val token = StableIds.from(
                "webview-target",
                tokenSequence.toString(),
                page.url,
                page.capturedAtEpochMs.toString(),
                element.fingerprint,
            )
            bindings[token] = TargetBinding(
                pageUrl = page.url,
                capturedAtEpochMs = page.capturedAtEpochMs,
                fingerprint = element.fingerprint,
            )
            ResolvedBrowserTarget(
                executionToken = token,
                pageUrl = page.url,
                fingerprint = element.fingerprint,
                role = element.role,
                accessibleName = element.accessibleName,
                tag = element.tag,
                inputType = element.inputType,
                visible = true,
                enabled = true,
                editable = element.tag in EDITABLE_TAGS,
                sensitivity = sensitivity(element.inputType),
            )
        }
    }

    override suspend fun perform(
        command: BrowserActionCommand,
        cancellationSignal: BrowserActionCancellationSignal,
    ): PlatformBrowserActionResult {
        val binding = bindings.remove(command.targetExecutionToken)
            ?: return rejected("target-token-absent")
        val page = currentContext() ?: return rejected("page-absent")
        if (
            binding.pageUrl != command.pageUrl ||
            binding.fingerprint != command.targetFingerprint ||
            page.url != binding.pageUrl ||
            page.capturedAtEpochMs != binding.capturedAtEpochMs ||
            page.interactiveElements.count { it.fingerprint == binding.fingerprint } != 1
        ) {
            return rejected("target-binding-stale")
        }
        if (cancellationSignal.isCancellationRequested()) {
            return PlatformBrowserActionResult.CancelledBeforeSideEffect
        }

        val response = try {
            decodeResponse(evaluator.evaluate(actionScript(command)))
        } catch (_: Throwable) {
            return failed("action-transport-unknown")
        }
        return when (response["state"]?.jsonPrimitive?.content) {
            "APPLIED" -> PlatformBrowserActionResult.Completed
            "DISPATCHED" -> PlatformBrowserActionResult.DispatchedAwaitingObservation
            "NONE" -> rejected(response["code"]?.jsonPrimitive?.content ?: "action-rejected")
            else -> failed(response["code"]?.jsonPrimitive?.content ?: "action-result-unknown")
        }
    }

    private fun decodeResponse(raw: String): JsonObject {
        val outer = json.parseToJsonElement(raw)
        val response = if (outer is JsonPrimitive && outer.isString) {
            json.parseToJsonElement(outer.content)
        } else {
            outer
        }
        return response.jsonObject
    }

    private fun actionScript(command: BrowserActionCommand): String {
        val data = buildJsonObject {
            put("pageUrl", command.pageUrl)
            put("targetFingerprint", command.targetFingerprint)
            put("kind", command.kind.name)
            command.expectedUrl?.let { put("expectedUrl", it) }
            when (val payload = command.payload) {
                ClickPayload -> Unit
                is FillTextPayload -> put("value", payload.value)
                is SelectOptionPayload -> put("value", payload.value)
            }
        }
        return FIXED_ACTION_SCRIPT.replace("__KAW_COMMAND_DATA__", data.toString())
    }

    private fun rejected(code: String) = PlatformBrowserActionResult.Rejected(
        code = canonicalCode(code),
        message = "Typed WebView action rejected",
    )

    private fun failed(code: String) = PlatformBrowserActionResult.Failed(
        code = canonicalCode(code),
        message = "Typed WebView action effect is unknown",
        retryable = false,
        sideEffectState = BrowserSideEffectState.UNKNOWN,
    )

    private fun canonicalCode(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(64).ifBlank { "unknown" }

    private fun sensitivity(inputType: String?): BrowserTargetSensitivity = when (inputType?.lowercase()) {
        "password" -> BrowserTargetSensitivity.PASSWORD
        "cc-number", "cc-csc", "cc-exp", "credit-card", "payment" -> BrowserTargetSensitivity.PAYMENT
        "file", "hidden" -> BrowserTargetSensitivity.SECRET
        else -> BrowserTargetSensitivity.NONE
    }

    private data class TargetBinding(
        val pageUrl: String,
        val capturedAtEpochMs: Long,
        val fingerprint: String,
    )

    private companion object {
        val EDITABLE_TAGS = setOf("input", "textarea", "select")

        val FIXED_ACTION_SCRIPT = """
            (() => {
              'use strict';
              const command = __KAW_COMMAND_DATA__;
              const result = (state, code) => JSON.stringify({ state: state, code: code || '' });
              if (window.location.href !== command.pageUrl) return result('NONE', 'page-url-changed');
              const normalize = value => (value || '').replace(/\s+/g, ' ').trim();
              const fnv1a = value => {
                let hash = 0x811c9dc5;
                for (let i = 0; i < value.length; i += 1) {
                  hash ^= value.charCodeAt(i);
                  hash = Math.imul(hash, 0x01000193);
                }
                return (hash >>> 0).toString(16).padStart(8, '0');
              };
              const name = element => normalize(
                element.getAttribute('aria-label') ||
                element.getAttribute('title') ||
                element.getAttribute('alt') ||
                element.getAttribute('placeholder') ||
                element.innerText
              ).slice(0, 240);
              const fingerprint = element => fnv1a([
                element.tagName,
                element.getAttribute('role'),
                element.id,
                element.getAttribute('name'),
                name(element),
                element.getAttribute('href')
              ].join('|'));
              const candidates = Array.from(document.querySelectorAll(
                'button, a[href], input, textarea, select, [role=button], [role=link], [contenteditable=true]'
              )).slice(0, 300);
              const matches = candidates.filter(element => fingerprint(element) === command.targetFingerprint);
              if (matches.length !== 1) return result('NONE', matches.length ? 'target-ambiguous' : 'target-absent');
              const element = matches[0];
              const style = window.getComputedStyle(element);
              const rect = element.getBoundingClientRect();
              if (
                !element.isConnected ||
                style.visibility === 'hidden' ||
                style.display === 'none' ||
                rect.width <= 1 ||
                rect.height <= 1 ||
                element.disabled === true ||
                element.getAttribute('aria-disabled') === 'true'
              ) return result('NONE', 'target-not-executable');
              const type = String(element.getAttribute('type') || '').toLowerCase();
              const metadata = [
                type,
                element.getAttribute('name') || '',
                element.getAttribute('id') || '',
                element.getAttribute('autocomplete') || ''
              ].join(' ').toLowerCase();
              if (
                ['password', 'file', 'hidden'].includes(type) ||
                /cc-|payment|credit|debit|card|cvv|cvc|secret|token|api-key|private-key|otp|one-time|verification-code/.test(metadata)
              ) return result('NONE', 'sensitive-target');

              if (command.kind === 'CLICK') {
                if (element.tagName.toLowerCase() !== 'a' || typeof command.expectedUrl !== 'string') {
                  return result('NONE', 'click-contract-invalid');
                }
                let actual;
                let expected;
                try {
                  actual = new URL(element.getAttribute('href') || '', window.location.href);
                  expected = new URL(command.expectedUrl);
                } catch (_) {
                  return result('NONE', 'click-url-invalid');
                }
                if (
                  actual.protocol !== 'https:' ||
                  expected.protocol !== 'https:' ||
                  actual.origin !== window.location.origin ||
                  expected.origin !== window.location.origin ||
                  actual.href !== expected.href
                ) return result('NONE', 'click-destination-mismatch');
                element.click();
                return result('DISPATCHED', 'awaiting-url-observation');
              }

              let inputEvents = 0;
              let changeEvents = 0;
              const onInput = () => { inputEvents += 1; };
              const onChange = () => { changeEvents += 1; };
              element.addEventListener('input', onInput);
              element.addEventListener('change', onChange);
              try {
                if (command.kind === 'FILL_TEXT') {
                  const tag = element.tagName.toLowerCase();
                  if (!['input', 'textarea'].includes(tag) || typeof command.value !== 'string') {
                    return result('NONE', 'fill-contract-invalid');
                  }
                  const prototype = tag === 'input' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
                  const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
                  if (typeof setter !== 'function') return result('NONE', 'fill-setter-absent');
                  setter.call(element, command.value);
                  element.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                  element.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                  return element.value === command.value && inputEvents > 0 && changeEvents > 0
                    ? result('APPLIED') : result('UNKNOWN', 'fill-postcondition-inconclusive');
                }
                if (command.kind === 'SELECT_OPTION') {
                  if (element.tagName.toLowerCase() !== 'select' || typeof command.value !== 'string') {
                    return result('NONE', 'select-contract-invalid');
                  }
                  const options = Array.from(element.options).filter(option => option.value === command.value);
                  if (options.length !== 1) return result('NONE', 'select-option-not-exact');
                  element.value = command.value;
                  element.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                  element.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
                  return element.value === command.value && changeEvents > 0
                    ? result('APPLIED') : result('UNKNOWN', 'select-postcondition-inconclusive');
                }
                return result('NONE', 'action-kind-unsupported');
              } finally {
                element.removeEventListener('input', onInput);
                element.removeEventListener('change', onChange);
              }
            })();
        """.trimIndent()
    }
}
