# DeepSeek Harness Streamable HTTP bridge contract

This directory documents the host-side boundary for issue #29. The portable implementation lives in:

```text
composeApp/src/commonMain/kotlin/dev/ed3c/autowebview/mcp/http/
```

It is not a runnable HTTP server and does not open a port.

## Intended topology

```text
DeepSeek Harness
  -> @deepseek-ai/dsh-mcp-client
  -> private HTTPS or explicit loopback HTTP
  -> host-owned server adapter
  -> McpStreamableHttpBridge
  -> BrowserMcpGateway
```

For Android, iOS, and Wasm, do not add an Internet-reachable inbound listener as a convenience fallback. Prefer a private host-side bridge or admitted sandbox. An optional Desktop-local listener is a later platform slice and requires its own threat model and evidence.

## Mount contract

A host adapter must provide one `McpHttpBridgeRequest` per POST:

```kotlin
val request = McpHttpBridgeRequest(
    method = trustedHttpRequest.method,
    scheme = trustedServerScheme,
    authority = trustedServerAuthority,
    path = trustedHttpRequest.path,
    query = trustedHttpRequest.rawQuery,
    headers = trustedHttpRequest.headersAsLists(),
    body = bodyReadWithinPolicyLimit,
    declaredContentLength = trustedHttpRequest.contentLength,
)

val response = bridge.handle(
    request = request,
    nowEpochMs = clock.nowEpochMilliseconds(),
)
```

The example is pseudocode. No specific server framework is admitted by this slice.

The host must not derive `scheme` or `authority` from an arbitrary forwarding header unless a trusted proxy policy has already validated and rewritten that value.

## Body streaming requirement

`McpStreamableHttpBridge` verifies the materialized body's UTF-8 size and the declared length. Because it owns no socket, it cannot prevent a server framework from buffering an oversized body first.

A concrete server must therefore:

1. reject a declared length above `maxRequestBodyBytes` before reading;
2. count bytes while streaming the body;
3. stop reading once the budget is exceeded;
4. provide only the bounded body to the portable bridge;
5. avoid placing rejected body bytes in logs or error pages.

## Authentication verifier

Production code supplies `McpHttpAuthenticationVerifier`:

```kotlin
val verifier = McpHttpAuthenticationVerifier { input ->
    // Pseudocode only. Use a constant-time or externally verified mechanism.
    credentialService.verifyWithoutLogging(input.authorizationHeader)
}
```

The verifier returns either:

```kotlin
McpHttpAuthenticationDecision.Accepted(
    subjectId = opaqueStableSubject,
    credentialEpoch = opaqueRotationEpoch,
)
```

or a typed rejection. Raw tokens must not be returned, logged, serialized, or added to receipts.

## Endpoint profiles

### Local development

```kotlin
McpHttpEndpointPolicy(
    scheme = "http",
    authority = "127.0.0.1:3090",
    path = "/mcp",
    allowedOrigins = setOf("http://127.0.0.1:3080"),
    allowMissingOrigin = true,
)
```

Plain HTTP is accepted only for `localhost`, `127.0.0.1`, or `[::1]`.

### Private or remote host

```kotlin
McpHttpEndpointPolicy(
    scheme = "https",
    authority = "agent.example.invalid:443",
    path = "/mcp",
    allowedOrigins = emptySet(),
    allowMissingOrigin = true,
)
```

`allowMissingOrigin = true` supports non-browser clients such as Node-based DeepSeek Harness. When an `Origin` header is present, it must still match the exact configured allowlist; with an empty allowlist, every present Origin is rejected.

TLS, DNS, private routing, authentication material, rate limiting, and service identity remain host-owned.

## Wire behavior

| Input | Output |
|---|---|
| `initialize` request | HTTP 200 JSON |
| `notifications/initialized` | HTTP 202, empty body |
| `tools/list` | HTTP 200 JSON |
| admitted `tools/call` | HTTP 200 JSON-RPC result/error |
| GET or session stream | Explicit rejection |
| invalid route/media/auth/body | Typed HTTP error before gateway |
| invalid gateway response | HTTP 502 |
| cancellation | Propagated; receipt is `UNKNOWN` after invocation |

The current bridge returns JSON responses only. It does not implement request-scoped SSE output or protocol-level HTTP sessions.

## Admitted tools

```text
browser_capture_context
browser_propose_navigation
```

DeepSeek Harness exposes them under the server-qualified names defined in ADR-0010. `browser_propose_navigation` remains a proposal-producing tool. A successful HTTP response does not prove navigation or any other native side effect occurred.

## Replay scope

Exact duplicate `tools/call` bodies from the same opaque subject and credential epoch are rejected inside a bounded window. Discovery and initialization may repeat so DeepSeek Harness reconnect can recover.

This duplicate guard is not a cryptographic request signature. Deployments needing stronger remote replay protection must add a signed nonce, workload identity proof, or equivalent mechanism in the host/authentication layer.

## Receipt boundary

A bridge receipt may contain only:

```text
outcome class
RPC method
HTTP status
safe typed error code
gateway-invoked flag
side-effect evidence
```

It must not contain:

```text
endpoint or Origin
Authorization value
subject identity
request or response body
tool arguments
page context
private URLs
```

## Verification status

Portable tests cover the legacy DeepSeek Harness sequence, sanitized context, proposal-only navigation, transport/auth rejection, exact duplicate rejection, response-ID validation, and cancellation evidence.

Still separate:

```yaml
real_listener: NOT_IMPLEMENTED
real_deepseek_harness_process: NOT_EXERCISED
production_authentication: NOT_IMPLEMENTED
request_scoped_sse: NOT_IMPLEMENTED
mobile_listener: DENIED_AS_DEFAULT_TOPOLOGY
physical_devices: NOT_EXERCISED
merge: EXTERNAL_AUTHORITY_REQUIRED
```
