# DeepSeek Harness Desktop loopback listener

This directory documents the Desktop-only substrate owned by issue #33. The implementation lives under:

```text
composeApp/src/desktopMain/kotlin/dev/ed3c/autowebview/mcp/http/
```

## Status

```yaml
listener_class: IMPLEMENTED_IN_STACK_SLICE
application_auto_start: NOT_IMPLEMENTED
deepseek_harness_process: NOT_EXERCISED
production_token_source: NOT_IMPLEMENTED
remote_tls: NOT_IMPLEMENTED
mobile_listener: DENIED_BY_ARCHITECTURE
```

The listener remains default-off. Merely building or launching the Desktop application does not open a port.

## Fixed topology

```text
DeepSeek Harness
  -> @deepseek-ai/dsh-mcp-client
  -> http://127.0.0.1:<explicit-port>/mcp
  -> DesktopMcpLoopbackServer
  -> DesktopMcpBearerAuthenticationVerifier
  -> McpStreamableHttpBridge
  -> BrowserMcpGateway
  -> sanitized read OR typed proposal
  -> local Capability Policy / Dispatcher / HITL / Executor
```

The listener bind host cannot be configured. It uses numeric IPv4 loopback only. Android, iOS, and Wasm do not receive an inbound listener.

## Cordis side

The parent compatibility lane renders a default-off DeepSeek Harness patch that resolves the bearer token in the DeepSeek Harness host process:

```yaml
serverName: kotlin_auto_webview
transport: streamable-http
url: http://127.0.0.1:3090/mcp
headers:
  Authorization: <resolved from KOTLIN_AUTO_WEBVIEW_MCP_TOKEN at runtime>
```

No token value belongs in the repository, generated patch, issue, PR, log, or test receipt.

## Application side

A host integration must explicitly provide:

```text
DesktopMcpLoopbackServerConfig.runtime(enabled=true, explicit port)
BrowserMcpGateway bound to the current AgentBrowserRuntime
bearer token bytes obtained from an admitted secret source
```

The current stack slice does not choose a production secret source and does not wire listener startup into `main.kt`. Token generation, secure storage, process inheritance, rotation, revocation, and crash handling remain separate work.

The caller owns the token byte array it supplies. It should erase its own mutable copy after listener construction. The listener retains only a SHA-256 digest and erases that digest on close.

## HTTP contract

```http
POST /mcp
Host: 127.0.0.1:<bound-port>
Content-Type: application/json
Accept: application/json, text/event-stream
Authorization: Bearer <runtime secret>
```

Supported portable MCP flow:

```text
initialize
notifications/initialized
ping
tools/list
tools/call browser_capture_context
tools/call browser_propose_navigation
```

Request-scoped SSE responses and protocol-level HTTP sessions remain unsupported. `notifications/initialized` returns HTTP 202 with an empty body.

## Resource controls

The listener enforces:

- bounded backlog;
- fixed worker count;
- bounded worker queue;
- declared Content-Length ceiling before reading;
- running byte ceiling for unknown or chunked bodies;
- strict UTF-8;
- exact Host, path, and empty query;
- repeated singleton security-header rejection;
- deterministic server/executor shutdown.

The portable bridge separately enforces Origin, media types, MCP session refusal, JSON-RPC shape, protocol metadata, tool allowlists, action replay suppression, gateway response bounds, and cancellation evidence.

## Authority boundary

```text
loopback connection
  != authenticated caller

authenticated caller
  != admitted MCP method/tool

MCP tool result
  != local browser/native side effect
```

`browser_propose_navigation` remains a typed proposal. It cannot navigate until local policy, dispatcher state, human confirmation, page freshness, and executor controls admit the action.

## Evidence limits

A green listener test proves only a local Desktop HTTP substrate against the KMP bridge. It does not prove:

```yaml
real_dsh_cli_startup: NOT_EXERCISED
cordis_profile_or_patch_loading: NOT_EXERCISED
ctx_tools_registration: NOT_EXERCISED
reconnect_or_hmr: NOT_EXERCISED
production_token_custody: NOT_IMPLEMENTED
oauth_or_mtls: NOT_IMPLEMENTED
remote_tls: NOT_IMPLEMENTED
desktop_packaging_with_jdk_httpserver: NOT_EXERCISED
physical_devices: NOT_EXERCISED
merge_or_release: EXTERNAL_AUTHORITY_REQUIRED
```
