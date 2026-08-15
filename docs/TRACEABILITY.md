# Architecture traceability

| Requirement | Code / evidence | Status |
|---|---|---|
| Android + iOS + Web + Desktop KMP targets | `composeApp/build.gradle.kts`, platform entry points | Implemented |
| Android WebView / iOS WKWebView / Desktop KCEF | replaceable `compose-webview-multiplatform` adapter | Implemented adapter |
| Shadow DOM / JS injection observer | `web/ContextExtractorScript.kt` | Implemented |
| Bidirectional JS bridge | `PageContextMessageHandler.kt` | Implemented |
| DOM fingerprint + geometry | injected `fingerprint` and `DomRect` contracts | Implemented |
| L1 local semantic cache | `cache/SemanticCache.kt` | Implemented in-memory |
| L2 OpenClaw semantic stream | authenticated transport contract and persistent stream | Planned |
| Context-to-screen projection | `projection/ProjectionEngine.kt`, `ProjectionOverlay.kt` | Implemented MVP |
| Capability registry | `capability/CapabilityRegistry.kt` | Implemented |
| User/agent arbitration | `dispatcher/LocalDispatcher.kt` + tests | Implemented |
| MCP discovery, resource, and bounded tools | `mcp/BrowserMcpGateway.kt` + common tests | Implemented transport-independent gateway |
| MCP network transport/authentication | platform adapters, identity, origins, rate limits | Planned; intentionally not opened by common core |
| Native device capabilities | expect/actual tools | Planned |
| Persistent SQLDelight cache/audit | database adapter | Planned |
| Local SLM/vector model | embedding adapter | Planned; lexical deterministic baseline only |
| Platform attestation | Play Integrity / App Attest | Planned |
| Android Play and iOS App Store signing | release runbooks | Configuration required |
