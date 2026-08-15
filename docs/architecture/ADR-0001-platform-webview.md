# ADR-0001 — Platform-native WebView behind a shared contract

**Status:** Accepted

## Decision

Use `compose-webview-multiplatform` as the replaceable adapter. Android remains Chromium-backed Android WebView; iOS remains WKWebView; desktop uses KCEF; Web/Wasm uses browser embedding rules. All agent logic stays in shared Kotlin and communicates through a narrow JavaScript message contract.

## Consequences

- We get one UI/runtime architecture without pretending rendering engines are identical.
- Chromium-only features require capability detection and fallback on iOS/WebKit.
- Chrome extensions are not part of the mobile architecture.
- The adapter is isolated enough to replace with first-party platform implementations if library maintenance or store policy changes.
