# DeepSeek Harness process E2E

This directory owns the repository-side specifications for pinned DeepSeek Harness/Cordis interoperability and startup-recovery tests.

## Exact evidence subject

```yaml
repository: deepseek-ai/deepseek-harness
commit: 47f943859bef60e4160492346772ded9b24f765a
version: 0.1.0-rc.5
node: 22.19.0
pnpm: 11.7.0
license: MIT
```

The CI workflow checks out that exact commit into an ephemeral directory, verifies the commit identity, installs its frozen lockfile with lifecycle scripts disabled, and copies the repository-owned TypeScript specifications into the upstream test directory. No upstream source is copied into this repository.

## Evidence lanes

### Immediate-connect interoperability

[`deepseek-harness-cordis.e2e.ts`](deepseek-harness-cordis.e2e.ts) runs after the Desktop listener is ready:

```text
Desktop JVM test
  -> real ephemeral 127.0.0.1 listener
  -> masked synthetic bearer token
  -> pinned upstream Vitest process
  -> Cordis Context + ToolRuntime
  -> @deepseek-ai/dsh-mcp-client
  -> initialize / notifications/initialized / tools/list
  -> ctx.tools generation
  -> browser_capture_context
  -> browser_propose_navigation
  -> JVM-side dispatcher oracle
```

### Initially unavailable endpoint recovery

[`deepseek-harness-startup-recovery.external.e2e.ts`](deepseek-harness-startup-recovery.external.e2e.ts) starts the pinned plugin before the listener exists:

```text
No listener on selected numeric-loopback port
  -> failOnStartupError=false
  -> first MCP attempt fails
  -> bounded reconnect supervisor starts
  -> external process writes initial-failure marker
  -> JVM starts listener on the exact port
  -> MCP client reconnects
  -> one current ctx.tools generation appears
  -> sanitized read and proposal-only navigation succeed
```

The marker is a control-plane synchronization artifact in a JVM-created temporary directory. It contains no token, endpoint, request, response, page context, or tool argument.

## Independent authority oracle

The external TypeScript subjects assert model-facing schemas and tool results. The owning JVM tests independently verify the local dispatcher reached `WAITING_FOR_CONFIRMATION` with the exact proposed URL.

```text
DeepSeek Harness process started
  != Cordis plugin activated

Cordis plugin activated
  != ctx.tools registration committed

ctx.tools registration committed
  != local capability enabled

MCP tool call succeeded
  != browser/native side effect occurred
```

The tests never invoke a model provider. They use synthetic page fixtures, a synthetic token, numeric loopback endpoints, and the two existing KMP MCP tools.

## Supply-chain and secret handling

- The upstream repository, Node version, pnpm version, and GitHub Action commits are exact.
- Installation uses the frozen upstream lockfile with lifecycle scripts disabled.
- The token is generated per CI run and masked immediately.
- The token is passed only through process environment and the transient Authorization header.
- Child output must not contain the token or secret fixture.
- Failure output is bounded and redacted before it can enter Gradle diagnostics.
- No request body, page context, tool argument, response payload, or upstream workspace is uploaded as an artifact.

## Evidence distinction

```text
immediate-connect PASS
  != startup-recovery PASS

startup-recovery PASS
  != reconnect after an established listener fails

reconnect PASS
  != Cordis HMR or tool-list generation replacement
```

## Deliberately not proved

```yaml
reconnect_after_established_listener_failure: NOT_EXERCISED
cordis_hmr: NOT_EXERCISED
tool_list_change_generation_replacement: NOT_EXERCISED
production_token_custody: NOT_IMPLEMENTED
oauth_or_mtls: NOT_IMPLEMENTED
remote_tls: NOT_IMPLEMENTED
desktop_application_auto_start: NOT_IMPLEMENTED
desktop_packaging: NOT_EXERCISED
physical_devices: NOT_EXERCISED
merge: EXTERNAL_AUTHORITY_REQUIRED
production: EXTERNAL_AUTHORITY_REQUIRED
```
