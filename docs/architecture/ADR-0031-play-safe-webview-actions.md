# ADR-0031: Play-safe own-WebView typed actions

Status: accepted for Stage 6 implementation on issue #72.

## Context

The common browser executor already owns Local Dispatcher admission, exact confirmation, zero/one/many target resolution, freshness, revalidation, user preemption and timeout semantics. Its platform contract intentionally has no raw JavaScript or selector field, but Android had no concrete `BrowserActionPlatform`.

Returning `PlatformBrowserActionResult.Completed` is authority-sensitive because the common executor interprets that as success. Therefore an Android adapter must not map `evaluateJavascript` callback completion, `WebMessage` delivery, DOM event dispatch or `HTMLElement.click()` return behavior directly to Completed.

## Decision

`PlaySafeWebViewBrowserActionPlatform` is bound to one in-process WebView and an explicit owned HTTPS-origin allowlist. It refuses to operate unless the compile-time Android profile is `PLAY_SAFE`.

The page bridge is one repository-owned fixed JavaScript program. No action payload, model text, MCP text, target fingerprint, URL, selector or coordinate is concatenated into executable JavaScript. Typed command values and expected target fields cross the native/page boundary only as JSON data on an Android `WebMessagePort`, and the port is transferred only to the exact current HTTPS origin.

The fixed bridge does not expose native APIs. It can only:

- enumerate a bounded top-document set of interactive elements using one fixed selector owned by the repository;
- generate a per-document nonce and opaque in-page target tokens;
- return sanitized target metadata and SHA-256 digests;
- perform the three closed action kinds already present in `BrowserActionContracts`: click, fill text and select option;
- probe the exact token again for deterministic postcondition evidence.

Cross-origin iframe traversal is absent. Password, file, payment and secret-like fields are marked sensitive and cannot execute. Fill/select values are sent as message data, are never concatenated into code or selectors, and native postcondition verification compares only SHA-256 value digests.

## Target identity and freshness

A native fingerprint binds the exact page URL, per-document nonce, bounded interactive ordinal, tag, role, sanitized accessible name and input type. Resolution returns the bridge's opaque token only when the requested fingerprint matches exactly. Before dispatch, the adapter probes the token and requires the same page URL, document nonce, ordinal and semantic metadata. A navigation creates a new JavaScript realm/nonce, so old tokens fail closed.

The adapter is intentionally stricter than fuzzy browser automation: there is no text search fallback, CSS/XPath supplied by a caller, nearest element, coordinate click or automatic navigation.

## Side-effect truth

After dispatch the adapter performs a separate fresh probe. `Completed` is emitted only when a deterministic postcondition is observed:

- click: the exact page URL changes, or a bounded digest of the document changes;
- fill text: the exact target remains bound and its current value digest equals the requested value digest;
- select option: the exact target remains bound and its current selected value digest equals the requested value digest.

A bridge callback or event dispatch without a verified postcondition is UNKNOWN, not success. Pre-dispatch bridge/target rejection is NONE. Cancellation after dispatch is UNKNOWN. This preserves the common executor's success contract without pretending that a transport callback proves an applied effect.

## Security boundary

Only explicit app-owned HTTPS origins are admitted. The adapter does not disable or alter CSP, mixed-content policy, TLS validation, permission prompts, file chooser, downloads, WebAuthn, payment UI, password manager, OS dialogs or browser security settings. It adds no AccessibilityService, Shizuku, root/shell, notification listener or inbound mobile MCP surface.

## Evidence ceiling

Unit/compile/package evidence proves the fixed bridge, exact-origin policy and deterministic postcondition rules compile and remain Play-safe. It does not prove arbitrary third-party sites, cross-origin frames, production origin ownership, Google Play approval, signing, merge, release or production readiness. Device/WebView timing and runtime fixtures converge under issue #74.
