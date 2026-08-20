# ADR-0031: Play-safe own-WebView typed actions

Status: accepted for Stage 6 implementation on issue #72.

## Context

The common browser executor already owns Local Dispatcher admission, exact confirmation, zero/one/many target resolution, freshness, revalidation, user preemption and timeout semantics. Its platform contract intentionally has no raw JavaScript or selector field, but Android had no concrete `BrowserActionPlatform`.

Returning `PlatformBrowserActionResult.Completed` is authority-sensitive because the common executor interprets it as successful execution. Therefore an Android adapter must not map `evaluateJavascript` callback completion, WebMessage delivery, DOM event dispatch or `HTMLElement.click()` return behavior directly to completion.

## Decision

`PlaySafeWebViewBrowserActionPlatform` is bound to one in-process WebView and an explicit owned HTTPS-origin allowlist. It refuses to operate unless the compile-time Android profile is `PLAY_SAFE`.

The page bridge is one repository-owned fixed JavaScript program. No action payload, model text, MCP text, target fingerprint, URL, selector or coordinate is concatenated into executable JavaScript. Typed command values and expected target fields cross the native/page boundary only as JSON data on an Android `WebMessagePort`, and the port is transferred only to the exact current HTTPS origin.

The fixed bridge does not expose native APIs. It can only:

- enumerate a bounded top-document set of interactive elements using one fixed selector owned by the repository;
- generate a per-document nonce and opaque in-page target tokens;
- return sanitized target metadata, SHA-256 field digests and bounded input/change event counters;
- perform the closed click/fill/select action kinds already present in `BrowserActionContracts`, subject to the narrower rules below;
- probe the exact token again for deterministic postcondition evidence.

Cross-origin iframe traversal is absent. Password, file, payment and secret-like fields are marked sensitive and cannot execute. Fill/select values are sent as message data, are never concatenated into code or selectors, and native postcondition verification compares only SHA-256 value digests.

## Target identity and freshness

A native fingerprint binds the exact page URL, per-document nonce, bounded interactive ordinal, tag, role, sanitized accessible name and input type. Resolution returns the bridge's opaque token only when the requested fingerprint matches exactly. Native target bindings expire after two seconds even if the page is otherwise static. Before dispatch, the adapter probes the token and requires the same page URL, document nonce, ordinal and semantic metadata. A navigation creates a new JavaScript realm/nonce, so old tokens fail closed.

The adapter is intentionally stricter than fuzzy browser automation: there is no text search fallback, caller-supplied CSS/XPath, nearest element, coordinate click or automatic navigation.

## Side-effect truth

### Click

The first click slice is limited to exact anchor navigation. `PlaySafeWebViewPolicy` binds an exact target fingerprint to one exact owned HTTPS destination URL. A click with no target-specific expectation rejects before side effect, and the native adapter also requires the resolved target tag to be `a`.

The expected URL crosses the bridge only as WebMessage data for comparison. The fixed bridge resolves the current anchor's existing `href` against the current document and requires that exact HTTPS destination to equal the expected URL before invoking `element.click()`. The bridge never calls `location.assign`, `location.replace`, sets `window.location`, constructs a caller-controlled selector, or otherwise executes the expected URL. A changed-but-wrong URL or unrelated DOM mutation is never proof of the click.

A bridge transport exception after an action request is always `UNKNOWN`, even if the WebView is later observed at the expected URL, because the adapter cannot prove that the click was dispatched. Only a received bridge `accepted` dispatch receipt followed by the exact declared destination postcondition may yield `Completed`.

State/attribute/visibility click expectations are deliberately deferred until they have their own typed adapter contract.

### Fill text

The exact target must remain bound in the same page generation. Completion requires all of:

- the pre-dispatch value digest was not already the requested value digest;
- a fresh exact target value digest equals the requested value digest;
- the bridge observed a newer `input` event counter;
- the bridge observed a newer `change` event counter.

### Select option

The exact target must remain bound in the same page generation. Completion requires the selected value digest to become the requested value digest and a newer `change` event counter. Option resolution is exact by value and ambiguous/missing options reject before mutation.

For every action, transport callback or event dispatch without the declared fresh postcondition is UNKNOWN, not success. Pre-dispatch bridge/target rejection is NONE. Cancellation after possible dispatch is UNKNOWN. This preserves the common executor's effect-truth contract.

## Security boundary

Only explicit app-owned HTTPS origins are admitted. Click navigation expectations must also stay inside that owned-origin set and are keyed by exact target fingerprint. The expected URL is comparison data only; the existing exact anchor href remains the page-side navigation source. The adapter does not disable or alter CSP, mixed-content policy, TLS validation, permission prompts, file chooser, downloads, WebAuthn, payment UI, password manager, OS dialogs or browser security settings. It adds no AccessibilityService, Shizuku, root/shell, notification listener or inbound mobile MCP surface.

## Evidence ceiling

Unit/compile/package evidence proves the fixed bridge, exact-origin policy, bounded token lifetime and deterministic postcondition rules compile and remain Play-safe. It does not prove arbitrary third-party sites, cross-origin frames, production origin ownership, WebView/device timing, Google Play approval, signing, merge, release or production readiness. Runtime fixture and device evidence converge under issue #74.
