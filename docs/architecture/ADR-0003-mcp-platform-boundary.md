# ADR-0003 — Keep the MCP protocol core portable; bind SDKs at supported edges

**Status:** Accepted

## Context

The product needs one Android/iOS/Web/Desktop KMP core, while the published target variants of an MCP SDK can differ from the application's target matrix. Treating an unavailable artifact as if it were common code creates false portability and blocks iOS/mobile builds.

The architecture also requires a strict separation between protocol messages and execution authority. Starting a network server inside `commonMain` would hide peer identity, origin, rate limiting, and lifecycle policy behind a convenient but unsafe abstraction.

## Decision

- Implement a transport-independent JSON-RPC MCP gateway in `commonMain`.
- Support stateless discovery and the legacy initialize/resources/tools flows needed by compatible clients.
- Expose sanitized resources and typed proposals only.
- Keep execution behind `CapabilityRegistry`, `LocalDispatcher`, and HITL.
- Do not start a listener or trust a peer in the shared core.
- Add the official Kotlin SDK only in an edge/platform module whose published target variants match that deployment target.

## Consequences

- Android, iOS, Web, and Desktop compile against the same tested protocol contract.
- Production transports must implement authentication, origin policy, replay protection, protocol headers/version negotiation, rate limiting, cancellation, and lifecycle shutdown.
- SDK-specific conveniences are isolated and replaceable.
- The repository does not claim unsupported mobile SDK coverage.
