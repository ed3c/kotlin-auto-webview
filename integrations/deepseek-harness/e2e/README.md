# DeepSeek Harness process E2E

This directory owns the repository-side specification for the pinned DeepSeek Harness/Cordis interoperability test.

## Exact evidence subject

```yaml
repository: deepseek-ai/deepseek-harness
commit: 47f943859bef60e4160492346772ded9b24f765a
version: 0.1.0-rc.5
node: 22.19.0
pnpm: 11.7.0
license: MIT
```

The CI workflow checks out that exact commit into an ephemeral directory, verifies the commit identity, installs its frozen lockfile with lifecycle scripts disabled, and copies [`deepseek-harness-cordis.e2e.ts`](deepseek-harness-cordis.e2e.ts) into the upstream test directory. No upstream source is copied into this repository.

## Data flow

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

The external TypeScript subject asserts only model-facing behavior. The owning JVM test independently verifies the local dispatcher reached `WAITING_FOR_CONFIRMATION` with the exact proposed URL.

## Evidence laws

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

The test never invokes a model provider. It uses a synthetic page fixture, synthetic token, numeric loopback endpoint, and the two existing KMP MCP tools.

## Secret and output handling

- The token is generated per CI run and masked immediately.
- The token is passed only through process environment and the transient Authorization header.
- Test output must not contain the token, endpoint, or secret fixture.
- Failure output is bounded and redacted before it can enter Gradle diagnostics.
- No request body, page context, tool argument, or response payload is uploaded as an artifact.

## Deliberately not proved

```yaml
cordis_hmr: NOT_EXERCISED
reconnect_after_listener_failure: NOT_EXERCISED
production_token_custody: NOT_IMPLEMENTED
oauth_or_mtls: NOT_IMPLEMENTED
remote_tls: NOT_IMPLEMENTED
desktop_application_auto_start: NOT_IMPLEMENTED
desktop_packaging: NOT_EXERCISED
physical_devices: NOT_EXERCISED
merge: EXTERNAL_AUTHORITY_REQUIRED
production: EXTERNAL_AUTHORITY_REQUIRED
```
