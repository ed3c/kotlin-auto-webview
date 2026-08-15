# Architecture and delivery traceability

This matrix is the requirement-to-owner-to-state-to-evidence index. Implementation state and evidence state remain separate. Planned issues/branches do not imply that code or live proof exists.

## Runtime architecture

| Requirement | Owner / code | State/contract | Implementation | Evidence | Next issue |
|---|---|---|---|---|---:|
| Android + iOS + Web/Wasm + Desktop KMP targets | `composeApp/build.gradle.kts`, platform entry points | platform lifecycle adapters | Implemented | CI `PASS` at `a449fac...` | release #2/#3/#5 |
| Native renderer law | Android WebView, iOS WKWebView, Desktop KCEF, Wasm adapter | platform-owned renderer lifecycle | Implemented adapter | build/link `PASS`; physical runtime partial | #2/#3/#5 |
| Observer injection | `web/ContextExtractorScript.kt` | `NOT_INJECTED -> OBSERVING -> EMITTING -> RETRY/DEGRADED` | Implemented | common/platform build `PASS` | executor hardening #11 |
| Bidirectional JS bridge | `web/PageContextMessageHandler.kt` | decode/validate/forward | Implemented | common tests/build `PASS` | #11 |
| DOM fingerprint + geometry | `PageContext`/interactive element contracts + observer script | current-page anchor evidence | Implemented | projection/serialization tests `PASS` | #11 |
| Sensitive input exclusion | Observer script + `privacy/PrivacyGuard.kt` | `RAW -> FILTERED -> REDACTED -> BOUNDED` | Implemented | privacy tests `PASS` | persistence control #8 |
| L1 semantic cache contract | `cache/SemanticCache.kt` | query/hit-miss/put/remove/clear | Implemented in memory | cache tests `PASS` | #8 |
| Persistent local memory | planned `persistence/**`, SQLDelight schema | migration/restart/retention/audit | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #7 -> #8 |
| OpenClaw L2 stream | planned `edge/**` | auth/order/replay/expiry/backpressure/reconnect | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | epic #4; #7 -> #9 |
| Context-to-screen projection | `projection/ProjectionEngine.kt`, UI overlay | match -> bubble or context rail; stale drop | Implemented MVP | projection tests `PASS` | semantic #12/#13 |
| Semantic router contract | planned `semantics/**` | deterministic adapter/fallback/budget | `NOT_IMPLEMENTED` as separate module | lexical baseline exists; contract `NOT_EXERCISED` | #12 |
| On-device embedding/SLM engine | planned semantic engine adapters | measured engine selection | `NOT_IMPLEMENTED` | physical-device evidence `NOT_EXERCISED` | #13 |
| Capability registry | `capability/CapabilityRegistry.kt` | unknown/disabled/missing/over-risk deny; low allow; medium/high confirm | Implemented | policy tests `PASS` | Toolmaker #10 |
| User/agent arbitration | `dispatcher/LocalDispatcher.kt` | explicit six-state machine | Implemented | state tests `PASS` | executor #11 |
| Runtime orchestration | `runtime/AgentBrowserRuntime.kt` | capture -> sanitize -> query -> project -> store -> audit | Implemented | common tests/build `PASS` | persistence/edge #8/#9 |
| MCP discovery/resource/proposal gateway | `mcp/BrowserMcpGateway.kt` | parse -> validate -> discover/read/propose -> result/error | Implemented transport-independent gateway | MCP common tests `PASS` | network #9 |
| MCP network identity/transport | planned platform/edge adapter | authenticated peer + origin/rate/replay/lifecycle | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #9 |
| Native capability contracts | planned `toolmaker/**` | typed availability/permission/risk contract | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #10 |
| Bounded browser action executor | planned `executor/**` | freshness + HITL + preemption + completion/failure | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #11 |
| Audit evidence | runtime bounded in-memory flow | append recent events | Implemented MVP | common tests/build `PASS` | persistent append-only #8 |
| Platform attestation | Android Play Integrity / iOS App Attest actuals | official-binary/device integrity | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | #2/#3 |

## Security and policy invariants

| Invariant | Owner | Current mechanism | Evidence | Remaining gate |
|---|---|---|---|---|
| No raw model execution | `mcp/`, `capability/`, `dispatcher/` | typed `AgentAction` proposals only | common tests/build `PASS` | executor #11 mutation controls |
| Sensitive fields never enter context | `web/`, `privacy/` | JS exclusion + Kotlin redaction | privacy tests `PASS` | persistence #8 secret-at-rest controls |
| User input preempts agent | `dispatcher/`, `ui/` | `OBSERVING_USER` transition/defer | dispatcher tests `PASS` | executor cancellation #11 |
| Protocol is not authority | `mcp/`, `capability/`, `dispatcher/` | MCP cannot call platform APIs | common tests/static boundary `PASS` | edge #9/executor #11 |
| Projection is evidence-linked | `projection/`, domain contracts | cache ID, anchor, relevance, mode | projection tests `PASS` | stale/live anchor validation #11 |
| Platform differences are explicit | platform source sets + docs | native renderer/fallback constraints | compile/link `PASS`; device proof partial | #2/#3/#5 |
| Remote L2 cannot bypass local policy | planned `edge/` -> semantic/policy pipeline | architecture law only | `NOT_IMPLEMENTED` | #9 |
| Receipts contain no secrets | planned Git/edge/persistence receipts | documentation policy only | `NOT_IMPLEMENTED` | #8/#9 and Git wrapper |

## Delivery lanes

| Delivery requirement | Current artifact | Evidence state | Completion contract | Issue |
|---|---|---|---|---:|
| Android debug integration | Debug APK artifact | `PASS` build only | not a store claim | PR #1 |
| Google Play release | none signed | `NOT_EXERCISED` | production ID, signed AAB, Play App Signing, device/pre-launch/privacy/attestation receipts | #2 |
| iOS architecture integration | Simulator ARM64 framework | `PASS` link only | not an app-store claim | PR #1 |
| TestFlight/App Store | no signed archive | `NOT_EXERCISED` | Bundle/Team/signing, Organizer validation, TestFlight, physical devices, privacy/attestation receipts | #3 |
| Web production build | Wasm distribution artifact | `PASS` build only | not a deployment claim | PR #1 |
| Web publication | no verified deployed URL | `NOT_EXERCISED` | HTTPS/MIME/cache/browser/accessibility/CSP/origin smoke receipts | #5 |
| Desktop package/release | compile entry point | compile `PASS`; package `NOT_EXERCISED` | installer/signing/runtime/package-size evidence | future task |

## Git Town and Stacked PR governance

| Requirement | Owner / file | Implementation | Evidence | Blocker / issue |
|---|---|---|---|---|
| Shared canonical Skill without shadowing | `AGENTS.md`, `docs/git/README.md` | Implemented pointer | static review `PASS` | #6 |
| Static no-push Git Town policy | `.git-town.toml` | Implemented for selected v24.0.0 | static config `PASS` | executable still blocked |
| Repository profile | `docs/git/REPO_PROFILE.md` | Implemented | known/missing fields explicit | #6 |
| Stack graph and atomized issue index | `README*`, `docs/git/STACKED_PRS.md` | Implemented plan | planned branches are not reported as existing | #6; convergence #14 |
| Eval-first task packet | template + issues #6-#14 | Implemented documentation | issue bodies contain goals/paths/evals/controls | live Worker `NOT_EXERCISED` |
| Exact Git Town executable | `docs/git/GIT_TOWN_ADMISSION.md` | source release selected; host binary absent | `ABSENT` | trusted host admission |
| Isolated worktree/branch/path leases | planned wrapper/host roots | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | future governance implementation |
| Dry-run/no-push synchronization | Worker protocol | documented | `NOT_EXERCISED` | executable/wrapper admission |
| Planted conflict fail-closed canary | admission/harness docs | specified | `NOT_EXERCISED` | executable/wrapper admission |
| Machine-readable receipts | `receipts/git-town` planned | `NOT_IMPLEMENTED` | `NOT_EXERCISED` | future governance implementation |
| Exact-head publication gate | repo + shared delivery policy | `NOT_IMPLEMENTED`; disabled | `NOT_EXERCISED` | future publication stack |
| Human Admit for merge/promotion | `AGENTS.md`, Worker protocol | enforced by instruction boundary | static review `PASS`; live decision external | human/trusted operator |

## Atomized issue/branch index

| Issue | Branch | Parent | Owned state/path | Status |
|---:|---|---|---|---|
| #1 | `feat/kmp-agent-browser-foundation` | `main` | executable architecture foundation | open draft; baseline CI `PASS` |
| #6 | `docs/agent-integration-stack-index` | foundation | Agent/docs/Git Town SSOT | current documentation stack |
| #7 | `build/runtime-dependency-admission` | docs stack | SQLDelight/Ktor build + legal evidence | `NOT_IMPLEMENTED` |
| #8 | `feat/persistent-memory` | #7 | persistence/schema/audit | `NOT_IMPLEMENTED` |
| #9 | `feat/openclaw-stream-contract` | #7 | authenticated edge stream | `NOT_IMPLEMENTED` |
| #10 | `feat/native-capability-contracts` | docs stack | Toolmaker contracts | `NOT_IMPLEMENTED` |
| #11 | `feat/accessibility-action-executor` | #10 | bounded executor | `NOT_IMPLEMENTED` |
| #12 | `feat/local-semantic-router-contract` | docs stack | semantic contract/fixtures | `NOT_IMPLEMENTED` |
| #13 | `feat/local-embedding-engine` | #12 | selected engine/platform adapters | `NOT_IMPLEMENTED` |
| #2 | `release/android-play-evidence` | #11 | Android delivery lane | `NOT_EXERCISED` |
| #3 | `release/ios-app-store-evidence` | #11 | Apple delivery lane | `NOT_EXERCISED` |
| #5 | `release/web-deployment-evidence` | docs stack | Web deployment lane | `NOT_EXERCISED` |
| #14 | `converge/release-readiness-index` | docs stack after admitted dependencies | shared evidence reconciliation | `NOT_IMPLEMENTED` |

See `docs/git/STACKED_PRS.md` for path leases, evals, negative controls, parallelism, and Human Admit order.

## Source-architecture corrections preserved by implementation

- Mobile WebViews use platform renderers and cannot host Chrome extensions; controlled observer injection and a JS bridge are used instead.
- KMP L1 and private L2 remain separate state/persistence/transport lanes.
- DOM fingerprint/geometry evidence is kept separate from action authorization.
- The official MCP Kotlin SDK is not forced into `commonMain` when its artifact target variants do not match the admitted matrix; the common core keeps a portable JSON-RPC protocol boundary and leaves official SDK/network adapters to compatible edge/platform modules.

These corrections are architecture decisions, not claims that every future component is complete.
