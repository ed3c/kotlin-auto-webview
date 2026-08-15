# DeepSeek Harness process E2E

This directory owns the repository-side specifications for pinned DeepSeek Harness/Cordis interoperability and recovery tests.

## Exact evidence subject

```yaml
repository: deepseek-ai/deepseek-harness
commit: 47f943859bef60e4160492346772ded9b24f765a
version: 0.1.0-rc.5
node: 22.19.0
pnpm: 11.7.0
license: MIT
```

The CI workflow checks out that exact commit into an ephemeral directory, verifies the commit identity, installs its frozen lockfile with lifecycle scripts disabled, and copies repository-owned TypeScript specifications into the upstream test directory. No upstream source is copied into this repository.

## Evidence lanes

### Immediate-connect interoperability — PR #36

[`deepseek-harness-cordis.e2e.ts`](deepseek-harness-cordis.e2e.ts) starts after the Desktop listener is ready and proves real Cordis plugin activation, `ctx.tools` registration, sanitized context, proposal-only navigation, and clean disposal.

### Initially unavailable endpoint recovery — PR #38

[`deepseek-harness-startup-recovery.external.e2e.ts`](deepseek-harness-startup-recovery.external.e2e.ts) starts the plugin before the listener exists:

```text
first attempt fails with no KMP tools
  -> fixed marker
  -> JVM starts listener
  -> bounded startup reconnect
  -> exact tools register
  -> sanitized read and proposal-only action
```

### Established-session listener replacement — diagnostic PR

[`deepseek-harness-established-session-recovery.external.e2e.ts`](deepseek-harness-established-session-recovery.external.e2e.ts) is a falsifiable probe:

```text
listener generation 1
  -> real connection and tool registration
  -> generation-1 context
  -> listener closes and port releases
  -> at least one tool call fails during outage
  -> listener generation 2 starts on same authority
  -> require upstream reconnected-and-re-synced signal
  -> require tool-registration generation replacement
  -> generation-2 context and proposal-only navigation
```

A tool call that merely begins succeeding after the endpoint returns is not enough. The probe requires both the upstream supervisor recovery signal and a replaced registration generation.

## Independent authority oracle

The external TypeScript subjects assert model-facing schemas and tool results. The owning JVM tests independently verify the local dispatcher reached `WAITING_FOR_CONFIRMATION` with the exact proposed URL.

```text
DeepSeek Harness process started != Cordis plugin activated
Cordis plugin activated != ctx.tools registration committed
ctx.tools registration committed != local capability enabled
MCP tool call succeeded != browser/native side effect occurred
```

The tests never invoke a model provider. They use synthetic page fixtures, synthetic tokens, numeric loopback endpoints, and the two existing KMP MCP tools.

## Supply-chain and secret handling

- Upstream repository, Node, pnpm, and GitHub Action subjects are exact.
- Installation uses the frozen upstream lockfile with lifecycle scripts disabled.
- Synthetic tokens are generated per CI run and masked immediately.
- Tokens pass only through process environment and transient Authorization headers.
- Child output must not contain tokens or secret fixtures.
- Failure output is bounded and redacted before entering Gradle diagnostics.
- Coordination markers contain fixed literals only.
- No request body, page context, tool argument, response payload, or upstream workspace is uploaded as an artifact.

## Evidence distinction

```text
immediate-connect PASS != startup-recovery PASS
startup-recovery PASS != established-session recovery PASS
established-session recovery PASS != Cordis HMR
reconnect PASS != arbitrary remote proxy or production network PASS
```

## Stable diagnostic outcomes

```text
PASS
  real transport loss caused bounded supervisor reconnect and generation replacement

FAIL_TRANSPORT_CLOSE_NOT_OBSERVED
  outage/recovery occurred without the required upstream onclose/re-sync evidence

FAIL_RECONNECT_EXHAUSTED
  supervisor entered reconnect but did not recover within the bounded subject

FAILED_EVAL
  another owning oracle failed
```

## Deliberately not proved

```yaml
cordis_hmr: NOT_EXERCISED
tool_list_change_notification_generation_replacement: NOT_EXERCISED
arbitrary_remote_proxy_behavior: NOT_EXERCISED
production_token_custody: NOT_IMPLEMENTED
oauth_or_mtls: NOT_IMPLEMENTED
remote_tls: NOT_IMPLEMENTED
desktop_application_auto_start: NOT_IMPLEMENTED
desktop_packaging: NOT_EXERCISED
physical_devices: NOT_EXERCISED
merge: EXTERNAL_AUTHORITY_REQUIRED
production: EXTERNAL_AUTHORITY_REQUIRED
```
