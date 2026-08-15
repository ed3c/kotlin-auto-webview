# Harness, eval, and evidence contract

This directory defines how implementation claims become reproducible evidence. Evals are designed before implementation branches are created.

## Evidence states

Use only:

```text
PASS
FAIL
ABSENT
NOT_IMPLEMENTED
NOT_EXERCISED
SKIPPED_BY_POLICY
```

Examples:

- code present but no test: `NOT_EXERCISED`, not PASS;
- required dependency/executable identity missing: `ABSENT`;
- planned adapter not written: `NOT_IMPLEMENTED`;
- draft workflow intentionally suppressed: `SKIPPED_BY_POLICY`;
- command exit `0` with failed postconditions: `FAIL` / `FAILED_EVAL`.

## Evidence lanes

Keep each lane independent:

| Lane | Meaning |
|---|---|
| Static review | Source/config/doc contract is structurally present |
| Local sync | Git Town ancestry synchronization only |
| Local verification | Exact local HEAD passed task evals and controls |
| Publication decision | Gate allowed/blocked one explicit intent |
| Remote publication | Branch/PR operation actually occurred |
| Remote ancestry | Fetched remote equals admitted local subject and parent graph |
| GitHub trusted check | Exact remote head received runner-backed CI result |
| Runtime/device | Physical renderer, lifecycle, permissions, performance, network behavior |
| Store/release | Signed artifact, validation, distribution/pre-launch/TestFlight/deployment proof |
| Human Admit | Merge, promotion, release, rollback decision |

One lane cannot proxy another.

## Baseline full matrix

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:wasmJsBrowserDistribution
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64  # macOS
```

Current baseline head `a449fac24b8ee602b3c36ae60e972fe25f35c516` passed the corresponding GitHub CI jobs. Future work must rerun on its exact head.

## Eval routing by module/state boundary

| Module | Positive eval focus | Negative/mutation focus |
|---|---|---|
| `domain/` | round-trip serialization, defaults, stable IDs, bounded data | malformed/unknown fields, unstable IDs, oversized payloads |
| `web/` | idempotent injection, DOM cleanup, selection, element fingerprint/geometry, throttling | duplicate observer, password/payment extraction, bridge spoof, mutation storm |
| `privacy/` | redaction, truncation, element filtering, selection bounds | API keys/tokens/cards/private keys surviving sanitize |
| `cache/` | deterministic ranking, put/query/remove/clear, tie ordering | empty/poisoned records, secret persistence, nondeterministic order |
| `persistence/` (planned #8) | migrations, restart, retention, deletion, corruption recovery | destructive migration, rollback drift, secret-at-rest fixture |
| `projection/` | relevance threshold, anchor selection, bubble vs context rail, max count | stale/wrong fingerprint, missing elements, score overriding safety |
| `semantics/` (planned #12/#13) | fixed corpus precision/recall, latency/memory/package budget, fallback | vendor claim without measurement, unsupported target, remote leakage |
| `mcp/` | parse/validate/discovery/read/tool schema, typed proposal, error codes | invalid JSON-RPC, wrong method/URI, insecure URL, direct execution |
| `edge/` (planned #9) | identity, sequence, order, replay, expiry, cancellation, reconnect, backpressure | anonymous peer, wrong origin, replay, stale chunk, leaked key/log |
| `capability/` | register/enable/permission/risk decision matrix | duplicate/unknown/disabled/missing permission/over-risk |
| `dispatcher/` | every event/state transition, HITL, user preemption, suspend/resume | proposal during user input, confirm without pending action, hidden transition |
| `toolmaker/` (planned #10) | typed contracts and availability mapping | common code calling platform API; Tool without descriptor/audit |
| `executor/` (planned #11) | page/anchor freshness, confirmation, timeout, completion/failure | raw selector/model text, coordinate-only click, hidden/stale/sensitive target |
| `runtime/` | sanitize-query-project-store-audit ordering and bounded flows | cache/MCP before sanitize, denied action entering dispatcher, unbounded audit |
| `ui/` | projection/HITL rendering, pointer signals, accessible semantics | UI granting authority, blocked user input, fake completed state |
| platform source sets | lifecycle attach/detach, renderer behavior, fallback | platform policy divergence, resource leak, unsupported behavior hidden |
| Git Town governance | profile/config/task/path/receipt validation | local Skill shadow, wrong version, dirty/shared worktree, lease overlap, push mutation |

## Runtime state-machine assertions

### Dispatcher

Every state/event pair must be either explicitly accepted or proven inert. The minimum transition suite covers:

```text
READY + UserInteractionStarted -> OBSERVING_USER
OBSERVING_USER + ActionProposed -> OBSERVING_USER (deferred)
OBSERVING_USER + UserInteractionEnded -> READY
READY + low-risk proposal -> PROPOSING
READY + medium/high proposal -> WAITING_FOR_CONFIRMATION
WAITING_FOR_CONFIRMATION + confirmed -> EXECUTING
WAITING_FOR_CONFIRMATION + rejected -> READY
PROPOSING/EXECUTING + completed/failed -> READY
READY + suspend -> SUSPENDED
SUSPENDED + resume -> READY
```

### Observation/privacy/cache/projection pipeline

Required ordering assertion:

```text
raw bridge payload
  -> decode
  -> sanitize
  -> publish current context
  -> query L1
  -> project matches against current anchors
  -> put sanitized record
  -> append bounded audit event
```

A mutation that queries/persists raw context before sanitization must fail.

### MCP/capability/dispatcher pipeline

Required ordering assertion:

```text
JSON-RPC parse/validate
  -> resource read OR typed action construction
  -> capability evaluation
  -> denied audit OR dispatcher proposal
  -> HITL when required
  -> future executor only after admitted state
```

A mutation that lets MCP call WebView/native APIs directly must fail.

## Semantic-routing benchmark contract

Issue #12 must define a checked-in, non-sensitive fixed corpus with:

- query/current-page text;
- candidate cache records;
- expected relevant/irrelevant labels;
- expected stable ordering for ties;
- stale-anchor cases;
- fallback cases;
- language/script coverage relevant to the product;
- latency and allocation measurement protocol.

Issue #13 evaluates each engine against the same corpus and physical-device protocol. A model is admitted only when target variants, direct/transitive license/notices, memory, package size, battery, and latency all meet the declared budget. Simulator or marketing numbers are insufficient.

## Persistence contract

Issue #8 must prove:

- clean install and reopen;
- versioned migrations;
- deterministic query parity with the in-memory baseline;
- append-only audit ordering;
- bounded retention and explicit deletion;
- corruption and partial-write recovery;
- no secret/payment/password fixtures persisted;
- rollback subject and migration evidence.

## OpenClaw/edge contract

Issue #9 must prove:

- mutually authenticated logical peers;
- ordered chunks and monotonic sequence handling;
- duplicate and replay rejection;
- expiry/stale context pruning;
- bounded buffering/backpressure;
- cancellation, reconnect, key rotation, and offline transitions;
- no remote message bypasses privacy, capability policy, dispatcher state, or HITL;
- logs/receipts contain no key, token, cookie, or page-secret values.

Physical Android/iOS-to-private-node receipts remain a later runtime lane.

## Release proof boundaries

### Android

`:composeApp:assembleDebug` proves a debug APK can be built. Issue #2 requires a signed release AAB, Play App Signing/upload-key evidence, privacy/Data Safety declarations, API/device matrix, pre-launch report, WebView/session/media/process-death coverage, and attestation evidence.

### iOS

`:composeApp:linkDebugFrameworkIosSimulatorArm64` proves the Kotlin framework links for the simulator. Issue #3 requires a complete signed Xcode archive, Organizer validation, TestFlight delivery, physical iPhone/iPad coverage, WKWebView lifecycle/session/media/file-upload behavior, privacy declarations, and App Attest evidence.

### Web

`:composeApp:wasmJsBrowserDistribution` proves a production artifact exists. Issue #5 requires an HTTPS deployment URL, correct Wasm MIME/cache behavior, direct-navigation/reload/browser/accessibility smoke tests, and explicit CSP/same-origin/iframe refusal behavior.

## Git Town controls

Live controls are currently `NOT_EXERCISED`; see `../git/GIT_TOWN_ADMISSION.md`.

Required mutation set after admission:

- shared/primary checkout write attempt;
- dirty worktree;
- wrong branch parent/PR base;
- duplicate branch lease;
- overlapping sibling path lease;
- missing task field;
- unresolved profile input;
- wrong Git Town version or asset checksum;
- credential-bearing remote;
- editor/credential prompt;
- deterministic semantic rebase conflict;
- timeout;
- unexpected remote ref movement;
- `--no-push` changed to `--push`;
- old-SHA local verification/CI;
- repeated feedback identity;
- billing-open retry;
- skipped draft workflow reported as PASS;
- automatic continue/skip/undo/ship/merge/promotion.

## Documentation and traceability evals

For issue #6 and convergence #14:

- every Markdown link points to a tracked or intentionally external authority;
- Mermaid node/branch identifiers render without unsupported syntax;
- README and `README.zh-TW.md` contain the same state/data and Stack PR identities;
- every requirement has an owner/path, implementation state, evidence state, and next issue;
- planned branches are not reported as existing;
- exact heads and CI claims refer to the current subject;
- unknown facts remain explicit.

## Receipt requirements

When implemented, each eval run records:

```text
schema/version
repository identity
issue/task-packet digest
exact head and parent heads
logical worktree and lease IDs
command/timeout/exit
bounded stdout/stderr digests
changed paths/refs
positive evals
negative controls
cleanup/residue
result
immutable rollback subject
human action required
```

No secret values or unbounded raw streams are stored.
