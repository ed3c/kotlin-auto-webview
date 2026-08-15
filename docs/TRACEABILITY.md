# Architecture, automation, and delivery traceability

This is the requirement-to-owner-to-state-to-evidence index. Implementation state, evidence state, operation authority, and delivery state remain separate. Planned issues/branches do not imply that code or live proof exists.

## Stable identifiers

```text
REQ-###    requirement
SM-###     state machine
DF-###     data flow
INV-###    invariant
UNK-###    unknown/probe
EVAL-###   verifier or negative control
WP-###     work packet
STACK-###  Stack PR slice
EV-###     evidence subject/receipt
```

## Autonomous dual-lane control plane

| Requirement | Directory / owner | SM/DF | Invariant | Eval/control | Work packet / branch / PR | Exact evidence | Status |
|---|---|---|---|---|---|---|---|
| `REQ-AUTO-001` Full automation is non-interactive but bounded by existing authority | `AGENTS.md`, `docs/automation/` | `SM-AUTO-001`, `DF-AUTO-001` | `INV-SAFE-001..007` | `EVAL-AUTO-001` reject question-based authority expansion | `WP-006` / `docs/agent-integration-stack-index` / PR #15 | repository profile + issue #6 task packet | Implemented documentation |
| `REQ-AUTO-002` Builder and Shadow Architect remain separate lanes | `docs/automation/README.md` | `SM-SHADOW-001`, `DF-AUTO-002` | architecture deltas receive L0-L3 | `EVAL-SHADOW-001` planted immutable-boundary delta must L3 | `WP-006` | automation SSOT | Implemented documentation; live multi-agent execution `NOT_EXERCISED` |
| `REQ-AUTO-003` Block only the unsafe transition and continue path-disjoint work | `docs/automation/`, `docs/git/WORKER_PROTOCOL.md` | `SM-AUTO-001`, `DF-STACK-001` | sibling path leases disjoint | `EVAL-AUTO-002` blocked transition must not starve admitted sibling | every Stack packet | protocol/static review | Implemented contract; live scheduler `NOT_EXERCISED` |
| `REQ-AUTO-004` Mutation admission cannot exceed run-start authority | `docs/automation/REPOSITORY_PROFILE.md` | `SM-SAFE-001`, `DF-AUTO-001` | no authority expansion | `EVAL-SAFE-001` reject branch-write→settings/merge jump | `WP-006` | GitHub metadata snapshot | `REMOTE_PR_ADMITTED`; merge `EXTERNAL_AUTHORITY_REQUIRED` |
| `REQ-AUTO-005` Publication and merge remain separate | `docs/automation/`, `docs/git/` | `SM-PUB-001`, `DF-PUB-001` | exact head/base, disclosure, ancestry, trusted CI, preauthorization | `EVAL-PUB-001` old-SHA/skipped/push-only controls | publication task packet | PR #15 open draft; auto-merge false | PR maintenance admitted; merge not admitted |
| `REQ-AUTO-006` Every run emits an exact-subject safety/evidence receipt | `docs/automation/`, `docs/harness/` | `DF-EVID-001`, `DF-SAFE-001` | no cross-subject promotion | `EVAL-EVID-001` stale SHA and missing lane controls | future receipt implementation | schema documented | `NOT_IMPLEMENTED` as machine receipt |

## Autonomous state machines

| ID | Owner | States / responsibility | Current evidence |
|---|---|---|---|
| `SM-AUTO-001` | autonomous orchestrator | Discover → snapshot → bind authority → model → packets/stack → implement safe slices → verify → commit/push/PR → merge gate or stable blocked result | documented; current run uses forge-only branch/PR lane |
| `SM-SHADOW-001` | Shadow Architect | Monitoring → L0/L1 or L2 reconciliation or L3 named-transition block | documented; live separate Shadow process `NOT_EXERCISED` |
| `SM-SAFE-001` | safety binder | Read → local worktree → branch write → remote PR → preauthorized merge | current ceiling `REMOTE_PR_ADMITTED`; local lane `NOT_EXERCISED`; merge `EXTERNAL_AUTHORITY_REQUIRED` |
| `SM-PUB-001` | publication gate | Local verified → disclosure → push → remote ancestry → trusted CI → PR → merge gate | PR #15 remote publication exists; general gate `NOT_IMPLEMENTED` |
| `SM-DISP-001` | `dispatcher/LocalDispatcher.kt` | `READY`, `OBSERVING_USER`, `PROPOSING`, `WAITING_FOR_CONFIRMATION`, `EXECUTING`, `SUSPENDED` | state tests `PASS` at baseline head |

## Data-flow index

| ID | Source → destination | Payload / identity | Ordering, failure, and egress law | Current evidence |
|---|---|---|---|---|
| `DF-AUTO-001` | Repository/forge → authority binder | immutable repository ID, visibility, owner, default branch, refs, available operation classes | Read-only snapshot precedes mutation; missing field reduces admission | GitHub metadata available; local checkout `NOT_EXERCISED` |
| `DF-AUTO-002` | Builder → Shadow Architect | material delta, new reachable state, authority/resource/failure change | Classified before next checkpoint; L3 blocks named transition only | documented |
| `DF-EVID-001` | Eval/CI → evidence index | exact SHA/tree, environment class, command/result, bounded logs/digests | No promotion across subject, environment, or evidence ladder | CI on baseline and governance heads; machine receipt pending |
| `DF-STACK-001` | Issue/task packet → Worker/branch | goal, parent/base/head, path lease, evals, controls, rollback, safety fields | Missing/overlap blocks branch; siblings remain independent | issues #2-#14 and templates |
| `DF-PUB-001` | Admitted branch → GitHub PR | exact head/base, disclosure result, rollback subject | Push/PR after admission; fetch verifies remote head/ancestry | PR #15; general publication gate pending |
| `DF-SAFE-001` | Preflight snapshot → postcondition verifier | visibility/access/legal/ref/topology/private-egress/local-state states | Unexpected mismatch is FAIL or stable blocked result | static/forge metadata; local lane `NOT_EXERCISED` |
| `DF-RUNTIME-001` | WebView → observer → privacy → runtime | raw then sanitized `PageContext` | sanitize before cache/projection/MCP/audit | common tests/build `PASS` |
| `DF-RUNTIME-002` | Runtime/L1 → projection/UI | sanitized context, `CacheMatch`, anchor fingerprint/geometry | stale/irrelevant anchors drop; no authorization transfer | projection tests `PASS` |
| `DF-RUNTIME-003` | MCP → capability → dispatcher → HITL | JSON-RPC then typed `AgentAction` | protocol cannot grant execution; denied/confirm paths explicit | MCP/policy/dispatcher tests `PASS` |
| `DF-RUNTIME-004` | OpenClaw L2 → semantic router → projection | future authenticated ordered chunks | auth/order/replay/expiry/backpressure/pruning precede render | `NOT_IMPLEMENTED`; #9/#12 |

## Immutable repository safety invariants

| Invariant | Owner | Enforcement | Oracle / negative control | Current evidence / result |
|---|---|---|---|---|
| `INV-SAFE-001` Repository visibility remains public | `docs/automation/`, forge metadata | no settings mutation; before/after compare | visibility mismatch → `BLOCKED_VISIBILITY` / FAIL | public before; postcondition required |
| `INV-SAFE-002` Owner, default branch, access, rulesets, protection, secrets remain unchanged | Agent policy + forge metadata | no settings/access/secret APIs | attempted mutation or metadata diff | no such mutation in task; exact postcheck required |
| `INV-SAFE-003` License, attribution, and usage-right meaning remain unchanged | `LICENSE`, `NOTICE`, dependency gate | legal files excluded except admitted additive notice task | blob/semantic diff without legal authority → `BLOCKED_USAGE_RIGHTS` | baseline LICENSE/NOTICE blob subjects recorded |
| `INV-SAFE-004` User local state is preserved | host/worktree policy | primary checkout read-only; isolated worktree; no clean/stash/reset | destructive command or dirty-state overwrite | current connector local lane `NOT_EXERCISED` |
| `INV-SAFE-005` Private material cannot leave admitted boundary | disclosure scan / data classification | no private repo/local/private provider egress | secret/private URL/path/customer/private-source fixture | public docs only; scan required before publication |
| `INV-SAFE-006` Host execution is least privilege | host/runtime policy | no sudo/global install/opaque installer/arbitrary shell/ambient secrets | planted unsafe command | current forge-only mutation; local execution `NOT_EXERCISED` |
| `INV-SAFE-007` Protected/perennial history and remote topology are preserved | Git/Stack policy | no force push/delete/remote/default-branch mutation/auto conflict resolution | before/after refs/topology/ancestry | `main` and foundation refs are preservation subjects |

## Runtime architecture

| Requirement | Owner / code | SM/DF | Implementation | Evidence | Next issue |
|---|---|---|---|---|---:|
| `REQ-RUN-001` Android + iOS + Web/Wasm + Desktop KMP targets | `composeApp/build.gradle.kts`, platform entry points | platform lifecycle adapters | Implemented | CI `PASS` at `a449fac...` | #2/#3/#5 |
| `REQ-RUN-002` Native renderer law | Android WebView, iOS WKWebView, Desktop KCEF, Wasm adapter | platform lifecycles | Implemented adapter | build/link `PASS`; physical runtime partial | #2/#3/#5 |
| `REQ-RUN-003` Observer injection and JS bridge | `web/ContextExtractorScript.kt`, `PageContextMessageHandler.kt` | `DF-RUNTIME-001` | Implemented | common/platform build `PASS` | #11 |
| `REQ-RUN-004` DOM fingerprint + geometry | domain contracts + observer | `DF-RUNTIME-002` | Implemented | projection/serialization tests `PASS` | #11 |
| `REQ-RUN-005` Sensitive input exclusion | observer + `privacy/PrivacyGuard.kt` | raw→filtered→redacted→bounded | Implemented | privacy tests `PASS` | #8 controls |
| `REQ-RUN-006` L1 semantic cache | `cache/SemanticCache.kt` | query/hit-miss/put/remove/clear | In-memory implemented | cache tests `PASS` | #8 |
| `REQ-RUN-007` Persistent local memory/audit | planned `persistence/**` | migration/restart/retention/audit | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #7 → #8 |
| `REQ-RUN-008` Authenticated OpenClaw L2 | planned `edge/**` | `DF-RUNTIME-004` | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #4; #7 → #9 |
| `REQ-RUN-009` Context-to-screen projection | `projection/ProjectionEngine.kt`, UI | match→bubble/context rail; stale drop | MVP implemented | projection tests `PASS` | #11/#12/#13 |
| `REQ-RUN-010` Semantic router contract | planned `semantics/**` | deterministic adapter/fallback/budget | separate module `NOT_IMPLEMENTED`; lexical baseline exists | contract `NOT_EXERCISED` | #12 |
| `REQ-RUN-011` On-device semantic engine | planned engine adapters | measured selection | `NOT_IMPLEMENTED` | physical-device evidence `NOT_EXERCISED` | #13 |
| `REQ-RUN-012` Capability registry | `capability/CapabilityRegistry.kt` | decision matrix | Implemented | policy tests `PASS` | #10 |
| `REQ-RUN-013` Human/Agent arbitration | `dispatcher/LocalDispatcher.kt` | `SM-DISP-001` | Implemented | state tests `PASS` | #11 |
| `REQ-RUN-014` Runtime orchestration | `runtime/AgentBrowserRuntime.kt` | `DF-RUNTIME-001/002/003` | Implemented | common tests/build `PASS` | #8/#9 |
| `REQ-RUN-015` MCP discovery/resource/proposal | `mcp/BrowserMcpGateway.kt` | `DF-RUNTIME-003` | Transport-independent gateway implemented | MCP tests `PASS` | #9 |
| `REQ-RUN-016` MCP network identity/transport | planned platform/edge adapter | authenticated peer/origin/rate/replay/lifecycle | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #9 |
| `REQ-RUN-017` Native capability contracts | planned `toolmaker/**` | availability/permission/risk | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #10 |
| `REQ-RUN-018` Bounded action executor | planned `executor/**` | freshness/HITL/preemption/completion | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #11 |
| `REQ-RUN-019` Platform attestation | Play Integrity / App Attest actuals | device integrity | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #2/#3 |

## Runtime security invariants

| Invariant | Owner | Current mechanism | Evidence | Remaining gate |
|---|---|---|---|---|
| `INV-RUN-001` No raw model execution | `mcp/`, `capability/`, `dispatcher/` | typed `AgentAction` proposals only | tests/build `PASS` | executor #11 mutation controls |
| `INV-RUN-002` Sensitive fields never enter context | `web/`, `privacy/` | JS exclusion + Kotlin redaction | privacy tests `PASS` | persistence #8 secret-at-rest controls |
| `INV-RUN-003` User input preempts Agent | `dispatcher/`, `ui/` | `OBSERVING_USER` transition/defer | dispatcher tests `PASS` | executor cancellation #11 |
| `INV-RUN-004` Protocol is not authority | `mcp/`, `capability/`, `dispatcher/` | MCP cannot call platform APIs | tests/static boundary `PASS` | edge #9/executor #11 |
| `INV-RUN-005` Projection is evidence-linked | `projection/`, domain | cache ID, anchor, relevance, mode | projection tests `PASS` | live anchor validation #11 |
| `INV-RUN-006` Platform differences explicit | platform source sets + docs | native renderer/fallback constraints | compile/link `PASS`; device proof partial | #2/#3/#5 |
| `INV-RUN-007` Remote L2 cannot bypass local policy | planned `edge/` pipeline | architecture law only | `NOT_IMPLEMENTED` | #9 |
| `INV-RUN-008` Receipts contain no secrets | automation/Git/edge/persistence receipt policies | documentation policy | machine implementation `NOT_IMPLEMENTED` | future wrapper + #8/#9 |

## Delivery lanes

| Requirement | Current artifact | Evidence state | Completion contract | Issue |
|---|---|---|---|---:|
| `REQ-DEL-001` Android debug integration | Debug APK artifact | `PASS` build only | not a store claim | PR #1 |
| `REQ-DEL-002` Google Play release | none signed | `NOT_EXERCISED` | production ID, signed AAB, Play App Signing, device/pre-launch/privacy/attestation | #2 |
| `REQ-DEL-003` iOS architecture integration | Simulator ARM64 framework | `PASS` link only | not an App Store claim | PR #1 |
| `REQ-DEL-004` TestFlight/App Store | no signed archive | `NOT_EXERCISED` | signing, Organizer validation, TestFlight, physical devices, privacy/attestation | #3 |
| `REQ-DEL-005` Web production build | Wasm distribution artifact | `PASS` build only | not a deployment claim | PR #1 |
| `REQ-DEL-006` Web publication | no verified deployed URL | `NOT_EXERCISED` | HTTPS/MIME/cache/browser/accessibility/CSP/origin receipts | #5 |
| `REQ-DEL-007` Desktop package/release | compile entry point | compile `PASS`; package `NOT_EXERCISED` | installer/signing/runtime/package-size evidence | future task |
| `REQ-DEL-008` Automatic merge | open draft PRs | `EXTERNAL_AUTHORITY_REQUIRED` | repository-owned preauthorization + checks/approvals/order | none currently |

## Git Town and Stacked PR governance

| Requirement | Owner / file | Implementation | Evidence | Blocker / issue |
|---|---|---|---|---|
| `REQ-GIT-001` Canonical Skills without shadowing | `AGENTS.md`, automation/Git READMEs | pointers implemented | static review `PASS` | #6 |
| `REQ-GIT-002` Static no-push Git Town policy | `.git-town.toml` | selected v24.0.0 | static config `PASS` | executable blocked |
| `REQ-GIT-003` Repository profiles | automation + Git profiles | implemented | known/missing fields explicit | #6 |
| `REQ-GIT-004` Stack graph/path leases | README*, `STACKED_PRS.md` | implemented plan | planned branches not reported existing | #6/#14 |
| `REQ-GIT-005` Eval-first safety-bound task packet | templates + issues | implemented docs | issue packets contain goals/paths/evals/controls/safety | live Worker `NOT_EXERCISED` |
| `REQ-GIT-006` Exact Git Town executable | `GIT_TOWN_ADMISSION.md` | source release selected; host binary absent | `ABSENT` | trusted host admission |
| `REQ-GIT-007` Isolated worktree/leases/local-state preservation | planned wrapper/host roots | `NOT_IMPLEMENTED` | current connector `NOT_EXERCISED` | future governance implementation |
| `REQ-GIT-008` Dry-run/no-push sync | Worker protocol | documented | `NOT_EXERCISED` | executable/wrapper admission |
| `REQ-GIT-009` Conflict fail-closed + sibling continuation | Worker/Harness docs | specified | `NOT_EXERCISED` | executable/scheduler admission |
| `REQ-GIT-010` Machine-readable receipts | planned receipt roots | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | future wrapper |
| `REQ-GIT-011` Exact-head publication/disclosure gate | repository + shared delivery policy | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | future publication stack |
| `REQ-GIT-012` Merge only with preauthorization | automation/Git profiles | instruction boundary implemented | repository auto-merge false | `EXTERNAL_AUTHORITY_REQUIRED` |

## Atomized work-packet and Stack index

| Stack ID | Issue | Branch | Parent | Owned state/path | Status |
|---|---:|---|---|---|---|
| `STACK-000` | #1 | `feat/kmp-agent-browser-foundation` | `main` | executable architecture foundation | landed in `main` |
| `STACK-006` | #6 | `docs/agent-integration-stack-index` | foundation | autonomous/Git/Harness/root documentation SSOT | landed in `main` |
| `STACK-007` | #7 | `build/runtime-dependency-admission` | docs stack | SQLDelight/Ktor build + legal evidence | landed in `main` |
| `STACK-008` | #8 | `feat/persistent-memory` | #7 | persistence/schema/audit | landed in `main` |
| `STACK-009` | #9 | `feat/openclaw-stream-contract` | #7 | authenticated edge stream | landed in `main` |
| `STACK-010` | #10 | `feat/native-capability-contracts` | docs stack | Toolmaker contracts | landed in `main` |
| `STACK-011` | #11 | `feat/accessibility-action-executor` | #10 | bounded executor | landed in `main` |
| `STACK-012` | #12 | `feat/local-semantic-router-contract` | docs stack | semantic contract/fixtures | landed in `main` |
| `STACK-013` | #13 | `feat/local-embedding-engine` | #12 | selected engine/platform adapters | `BLOCKED_EXTERNAL`: needs physical-device budgets and a licence decision |
| `STACK-002` | #2 | `release/android-play-evidence` | #11 | Android delivery lane | `BLOCKED_EXTERNAL`: Play account, signing keys, physical devices |
| `STACK-003` | #3 | `release/ios-app-store-evidence` | #11 | Apple delivery lane | `BLOCKED_EXTERNAL`: Apple account, provisioning, physical devices |
| `STACK-005` | #5 | `release/web-deployment-evidence` | docs stack | Web deployment lane | landed in `main`; live Pages run `NOT_EXERCISED` |
| `STACK-014` | #14 | this convergence pass | docs stack after dependencies | shared evidence reconciliation | reconciled here |

See `docs/git/STACKED_PRS.md` for exact path leases, evals, controls, parallelism, and merge order.

## Post-stack MCP hardening index

These slices landed with the same convergence pass. Each row names the ADR that owns its evidence
boundary; every `NOT_EXERCISED` or `NOT_IMPLEMENTED` below is deliberate and stated there.

| Issue | Subject | Owner | ADR | Evidence state |
|---:|---|---|---|---|
| #46 | Runtime credential issuance, rotation, custody, revocation | `DesktopMcpCredentialLifecycle` | [0019](architecture/ADR-0019-mcp-credential-lifecycle.md) | implemented + tested; OS-keychain custody `NOT_IMPLEMENTED` |
| #50 | Desktop application lifecycle for the listener | `DesktopMcpIntegration`, `DesktopMcpRuntimeProfile` | [0020](architecture/ADR-0020-desktop-mcp-application-lifecycle.md) | implemented + tested; packaged `jpackage` run `NOT_EXERCISED` |
| #47 | Remote HTTPS termination and trusted-proxy admission | `DesktopMcpHttpsServer`, `McpHttpTransportPolicy` | [0021](architecture/ADR-0021-remote-https-and-trusted-proxy.md) | implemented + real TLS test; production proxy behaviour `NOT_EXERCISED` |
| #48 | OAuth, mTLS, and workload-identity verifiers | `McpOAuthBearerVerifier`, `McpMutualTlsVerifier`, `McpWorkloadIdentityVerifier` | [0022](architecture/ADR-0022-production-mcp-authentication.md) | implemented + tested; JWKS fetch and OCSP `NOT_IMPLEMENTED` |
| #54 | Bounded request-scoped SSE responses | `McpStreamableHttpBridge`, `McpHttpSseEvent` | [0023](architecture/ADR-0023-request-scoped-sse-responses.md) | implemented + tested; disconnect cancellation `PARTIAL` |
| #53 | Durable and multi-node replay state | `DurableMcpHttpReplayGuard` | [0024](architecture/ADR-0024-durable-replay-state.md) | durability tested; multi-node coordination `IMPLEMENTED_NOT_EXERCISED` |
| #49 | Generated Cordis patch parsing, HMR, tool-list replacement | `DeepSeekHarnessCordisBinding` | [0014](architecture/ADR-0014-deepseek-harness-process-e2e.md) | `NOT_EXERCISED`: needs the pinned upstream workspace, run by the DeepSeek Harness E2E workflow |
| #51 | Android private-edge transport and physical pairing | Android source set | [0005](architecture/ADR-0005-openclaw-stream.md) | `BLOCKED_EXTERNAL`: physical device and private node required |
| #52 | iOS private-edge transport and physical pairing | iOS source set | [0005](architecture/ADR-0005-openclaw-stream.md) | `BLOCKED_EXTERNAL`: physical device and private node required |

## Unknown register

| Unknown | Classification | Cheapest probe | Current state |
|---|---|---|---|
| `UNK-001` Host OS/architecture and exact Git Town asset | blocking for live Git Town | trusted host admission + checksum | `ABSENT` |
| `UNK-002` Local checkout dirty state/worktrees/hooks | blocking for local mutation claim | local runtime snapshot | current connector `NOT_EXERCISED` |
| `UNK-003` Real Android/iOS renderer and lifecycle behavior | blocking for store/runtime claim | physical-device suites | `NOT_EXERCISED` |
| `UNK-004` Private OpenClaw transport semantics | blocking for L2 claim | #9 contract + simulator then device/private-node tests | `NOT_IMPLEMENTED` |
| `UNK-005` On-device semantic engine budget/license | blocking for shipping engine | #12 corpus then #13 measured admission | `NOT_IMPLEMENTED` |
| `UNK-006` Repository trusted-automation merge policy | blocking for auto-merge | repository-owned policy/ruleset evidence | `ABSENT`; `EXTERNAL_AUTHORITY_REQUIRED` |
| `UNK-007` Production security/legal/store acceptance | outside Agent authority | external review and platform processes | `EXTERNAL_AUTHORITY_REQUIRED` |

## Source-architecture classification and corrections

- Platform-native renderers are treated as a real constraint and implemented adapter boundary; mobile WebViews cannot host Chrome extensions, so controlled observer injection and a JS bridge are used instead.
- KMP L1 and private OpenClaw L2 are separate state, persistence, transport, and evidence lanes. L1 exists as an in-memory baseline; L2 remains `NOT_IMPLEMENTED`.
- DOM fingerprint/geometry supports projection evidence but does not authorize an action.
- Observer → Toolmaker → Orchestrator remains a staged design proposal: Observer foundation exists; Toolmaker/Executor/Orchestrator work remains in #10/#11 and later tasks.
- The official MCP Kotlin SDK is not forced into `commonMain` when artifact target variants do not match the admitted matrix; the common core keeps a portable JSON-RPC boundary and leaves compatible SDK/network adapters to edge/platform modules.
- Market, performance, model, vendor, legal, and product claims from source documents remain `EXTERNAL_CLAIM` or `UNKNOWN` until primary-source/runtime verification.

These corrections and classifications do not claim that every future component is complete.
