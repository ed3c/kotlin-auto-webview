# Kotlin Auto WebView

[繁體中文](README.zh-TW.md) · English

A production-oriented Kotlin Multiplatform browser shell for Android, iOS, Web/Wasm, and Desktop. It turns an embedded WebView from a passive renderer into a **bounded agent surface**: the app observes and sanitizes page context, retains a local L1 memory, projects relevant evidence back onto the page, exposes bounded MCP resources/proposals, and keeps state-changing authority behind deterministic policy and human confirmation.

> **Current baseline:** executable architecture MVP in draft PR #1. Head `a449fac24b8ee602b3c36ae60e972fe25f35c516` passed common tests, Desktop compilation, Wasm production distribution, Android debug assembly, and iOS Simulator ARM64 framework linking. Store delivery, persistent memory, authenticated OpenClaw L2, native action execution, and an admitted on-device semantic engine remain separate work.

## Integration truth

| Capability | Implementation | Evidence state | Owner / next gate |
|---|---|---|---|
| Android, iOS, Web/Wasm, Desktop entry points | Implemented | `PASS` at baseline head | PR #1 |
| WebView observer, DOM text, selection, fingerprints, geometry | Implemented | `PASS` through common/platform builds | `web/` |
| Sensitive-field exclusion and Kotlin redaction | Implemented | `PASS` common tests | `privacy/` |
| L1 semantic cache | In-memory deterministic baseline | `PASS` common tests | `cache/`; persistence #8 |
| OpenClaw L2 stream | Contract only in architecture | `NOT_IMPLEMENTED` | epic #4; transport #9 |
| Cache-to-DOM projection | Bubble/context-rail MVP | `PASS` common tests | `projection/` |
| Capability policy and human preemption | Implemented | `PASS` common tests | `capability/`, `dispatcher/` |
| Bounded browser action executor | No privileged executor yet | `NOT_IMPLEMENTED` | contracts #10; executor #11 |
| MCP | Portable JSON-RPC discovery/resource/proposal gateway | `PASS` common tests | `mcp/` |
| MCP peer authentication/network listener | Intentionally absent from common core | `NOT_IMPLEMENTED` | #9 |
| Local semantic router | Lexical ranking embedded in cache baseline | baseline implemented; extraction contract pending | #12 |
| On-device embedding/SLM engine | No engine selected | `NOT_IMPLEMENTED` | #13 |
| Android Play delivery | Debug APK only | `NOT_EXERCISED` as store evidence | #2 |
| iOS App Store/TestFlight delivery | Simulator framework only | `NOT_EXERCISED` as store evidence | #3 |
| Web deployment | Production Wasm artifact built | deployment `NOT_EXERCISED` | #5 |
| Git Town static policy | `.git-town.toml` for v24.0.0 | configuration present | issue #6 |
| Git Town executable admission/live sync | Host binary/checksum/canaries missing | `ABSENT` / `NOT_EXERCISED` | `docs/git/GIT_TOWN_ADMISSION.md` |
| Autonomous dual-lane policy | Repository binding and safety profile | documentation implemented; live local lane `NOT_EXERCISED` | `docs/automation/` |
| Automatic merge | No repository-owned preauthorization | `EXTERNAL_AUTHORITY_REQUIRED` | leave exact PR open |

`PASS`, `FAIL`, `ABSENT`, `NOT_IMPLEMENTED`, `NOT_EXERCISED`, `SKIPPED_BY_POLICY`, and `EXTERNAL_AUTHORITY_REQUIRED` are not interchangeable.

## Autonomous dual-lane control plane

This repository applies the user-supplied **Repository Autonomous Dual-Lane Integration + Shadow Architecture + Git Town System Prompt v2.0** through a thin repository binding. Portable laws remain in the canonical shared Skills; repository identity, state ownership, evals, task packets, and safety postconditions live here.

```mermaid
flowchart LR
    TASK[Task / issue / PDF / architecture source] --> B[Builder]
    TASK --> S[Shadow Architect]
    B -->|material delta| S
    S -->|L0 observe / L1 warn| B
    S -->|L2 reconcile| R[Architecture + evidence reconciliation]
    R --> B
    S -->|L3 block named transition| X[Stable blocked state]
    X -->|continue independent safe work| B
    B --> V[Exact-subject evals + negative controls]
    V --> D[Commit / push / PR gates]
    D --> REC[Evidence-bound receipt]
```

### Current automation admission

| Operation class | State | Boundary |
|---|---|---|
| Repository/forge inspection | `READ_ONLY_ADMITTED` | Same public repository |
| Feature-branch write | `BRANCH_WRITE_ADMITTED` | Declared PR branch, fast-forward only |
| Issue/PR publication | `REMOTE_PR_ADMITTED` | Exact head/base, disclosure scan, existing rights |
| Local linked worktree and dirty-state proof | `NOT_EXERCISED` | Current connector does not expose local checkout state |
| Live Git Town synchronization | `BLOCKED_POLICY` | Exact host binary, wrapper, leases, receipts, and canaries missing |
| Merge | `EXTERNAL_AUTHORITY_REQUIRED` | Repository does not preauthorize trusted automation; auto-merge is disabled |
| Store/release/production/settings/secrets/license changes | denied | Outside Agent authority |

A blocked transition does not trigger a question and does not stop independent safe slices. See [`docs/automation/README.md`](docs/automation/README.md) and [`docs/automation/REPOSITORY_PROFILE.md`](docs/automation/REPOSITORY_PROFILE.md).

## Runtime data flow

```mermaid
flowchart LR
    U[User input] -->|pointer down/up| DISP[Local Dispatcher]
    WV[Platform WebView / WKWebView / KCEF / Web] --> OBS[Observer injection]
    OBS -->|raw PageContext JSON| BRIDGE[PageContext message handler]
    BRIDGE --> PRIV[Privacy Guard]
    PRIV -->|sanitized PageContext| RT[AgentBrowserRuntime]
    RT -->|query| L1[(L1 Semantic Cache)]
    L1 -->|CacheMatch| PROJ[Projection Engine]
    RT -->|current anchors| PROJ
    PROJ -->|ProjectionHint| UI[Compose Overlay + Context Rail]
    RT -->|put sanitized record| L1
    RT --> AUDIT[(Bounded Audit Flow)]

    MCP[MCP JSON-RPC Gateway] -->|read sanitized context| RT
    MCP -->|typed AgentAction proposal| CAP[Capability Registry]
    CAP -->|Allowed / RequiresConfirmation / Denied| DISP
    DISP -->|HITL request| UI
    UI -->|confirm / reject| DISP
    DISP -. admitted typed action .-> EXEC[Platform Action Executor]
    EXEC -. planned #11 .-> WV

    L2[(OpenClaw private L2)] -. authenticated ordered stream, planned #9 .-> SEM[Semantic Router]
    SEM -. pruned CacheMatch .-> PROJ
```

### End-to-end invariants

1. Observation precedes action.
2. Raw page data is sanitized before cache, projection, MCP, or audit use.
3. A model or remote node can propose a typed action; it cannot grant authority.
4. User interaction preempts Agent authority.
5. Page identity and anchor freshness must be revalidated before future execution.
6. Web origin/CSP/iframe limitations are surfaced, never bypassed.
7. Repository visibility, ownership, access, default branch, license/usage rights, remote topology, and private-data boundary remain immutable under autonomous work.
8. Missing evidence remains missing; a green build cannot promote a local, physical-device, store, legal, merge, or production lane.

## Directory → state machine → data contract

The table distinguishes explicit code state machines from pipeline transition contracts. Only `dispatcher/` currently owns a serialized enum state machine; other runtime rows describe the required lifecycle of that module.

| Path | Domain / owner | State or transition responsibility | Inputs | Outputs | Forbidden coupling | Evidence / next slice |
|---|---|---|---|---|---|---|
| `domain/` | Shared contracts | `DEFINE -> SERIALIZE -> VALIDATE` | Constructor/decoder values | `PageContext`, `AgentAction`, `ProjectionHint`, cache/audit DTOs | I/O, platform APIs, policy decisions | serialization tests |
| `web/` | Browser observer | `NOT_INJECTED -> OBSERVING -> EMITTING -> RETRY/DEGRADED` | Page lifecycle, DOM/selection/mutation events | Raw `PageContext` JSON | Privileged execution, secret retention, authorization | observer/privacy controls |
| `privacy/` | Data boundary | `RAW -> FILTERED -> REDACTED -> BOUNDED` | Raw `PageContext` | Sanitized `PageContext` | Ranking, remote transport, permission decisions | privacy tests |
| `cache/` | L1 memory | `QUERY -> HIT/MISS`; `PUT -> STORED`; `REMOVE/CLEAR` | Sanitized text/records | Ranked `CacheMatch` | UI rendering, network identity, action execution | cache tests; #8 |
| `projection/` | Visual evidence | `MATCH -> ANCHORED/BUBBLE` or `UNMATCHED/CONTEXT_RAIL`; stale data drops | Current anchors + cache matches | `ProjectionHint` list | Authorization or direct DOM mutation | projection tests; #11/#12 |
| `mcp/` | Protocol gateway | `PARSE -> VALIDATE -> DISCOVER/READ/PROPOSE -> RESULT/ERROR` | JSON-RPC payload | Sanitized resource or typed proposal | Network listener, peer trust, WebView/native calls | MCP tests; #9 |
| `capability/` | Authorization policy | Unknown/disabled/missing/over-risk `-> DENIED`; low `-> ALLOWED`; medium/high `-> REQUIRES_CONFIRMATION` | `AgentAction`, granted permissions | `PolicyDecision` | Temporal execution state, UI rendering | policy tests; #10 |
| `dispatcher/` | Human/Agent temporal authority | `READY`, `OBSERVING_USER`, `PROPOSING`, `WAITING_FOR_CONFIRMATION`, `EXECUTING`, `SUSPENDED` | User/action lifecycle events | `DispatcherSnapshot` | Capability invention, platform implementation | state tests; #11 |
| `runtime/` | Pipeline orchestration | `CAPTURE -> SANITIZE -> QUERY -> PROJECT -> STORE -> AUDIT` | Page contexts, proposals, HITL events | StateFlows for context/projection/audit/dispatcher | Store packaging, transport identity | common tests; #8/#9 |
| `ui/` | Rendering/HITL | `RENDER -> OBSERVE_USER -> REQUEST_CONFIRMATION -> CONFIRM/REJECT -> RENDER` | Runtime flows and user input | UI events | Hidden authorization or raw model execution | UI/semantics tests |
| `androidMain/` | Android adapter | `CREATE -> ATTACH_RENDERER -> FOREGROUND/BACKGROUND -> DESTROY` | Android lifecycle + shared contracts | Android renderer/tool results | Android-only policy divergence | #2/#10/#11 |
| `iosMain/` + `iosApp/` | Apple adapter/host | `CREATE_CONTROLLER -> ATTACH_WKWEBVIEW -> ACTIVE/BACKGROUND -> RELEASE` | iOS lifecycle + shared contracts | iOS renderer/tool results | iOS-only policy divergence | #3/#10/#11 |
| `desktopMain/` | Desktop/KCEF adapter | `INIT_KCEF -> READY -> ACTIVE -> SHUTDOWN` | Desktop lifecycle + shared contracts | Desktop renderer/tool results | Replacing mobile constraints with Chromium assumptions | Desktop compile/runtime task |
| `wasmJsMain/` | Web/Wasm adapter | `BOOT -> MOUNT -> ACTIVE -> UNMOUNT` | Browser document/lifecycle | Web UI events | Same-origin/CSP bypass claims | #5 |
| `docs/automation/` | Autonomous control plane | `SM-AUTO-001`, `SM-SHADOW-001`, `SM-SAFE-001`, `SM-PUB-001` | Repository/task/evidence metadata | Admission, intervention, safety, final receipt contracts | Portable Skill duplication or invented runtime truth | issue #6 / PR #15 |
| `docs/git/` | Stack governance | Task admission, branch/lease/sync/publication contracts | Issues, refs, exact tool evidence | Stack graph, Worker outcomes, receipts | Runtime feature ownership or merge authority | issue #6; future wrapper |
| `docs/harness/` | Verification architecture | Invariant -> observer -> oracle -> control -> evidence | Exact subject + environment + command | Evidence state and receipt | Cross-subject evidence promotion | all implementation slices |

### Dispatcher state machine

```mermaid
stateDiagram-v2
    [*] --> READY
    READY --> OBSERVING_USER: UserInteractionStarted
    OBSERVING_USER --> READY: UserInteractionEnded
    READY --> PROPOSING: low-risk ActionProposed
    READY --> WAITING_FOR_CONFIRMATION: medium/high-risk ActionProposed
    OBSERVING_USER --> OBSERVING_USER: proposal deferred
    WAITING_FOR_CONFIRMATION --> EXECUTING: ActionConfirmed
    WAITING_FOR_CONFIRMATION --> READY: ActionRejected
    PROPOSING --> READY: ActionCompleted / ActionFailed
    EXECUTING --> READY: ActionCompleted / ActionFailed
    READY --> SUSPENDED: Suspend
    SUSPENDED --> READY: Resume
```

### Autonomous state-machine index

| ID | Owner | Purpose |
|---|---|---|
| `SM-AUTO-001` | Autonomous orchestrator | Discovery through furthest safe delivery state |
| `SM-SHADOW-001` | Shadow Architect | L0-L3 architecture-delta intervention |
| `SM-SAFE-001` | Safety binder | Read/local/branch/PR/merge admission without authority expansion |
| `SM-PUB-001` | Publication gate | Exact-head commit/push/PR/merge separation |
| `SM-DISP-001` | `dispatcher/` | Human input preemption and action lifecycle |

Detailed automation state/flow contracts are in `docs/automation/README.md`.

## Repository layout

```text
.git-town.toml                 # static no-push Git Town policy; not executable admission
AGENTS.md                      # repository-wide Agent authority
README.md / README.zh-TW.md    # architecture, state/data map, automation and Stack index

composeApp/
  src/commonMain/kotlin/dev/ed3c/autowebview/
    domain/                    # serializable contracts; no I/O
    web/                       # observer injection + PageContext bridge
    privacy/                   # filtering/redaction boundary
    cache/                     # L1 cache contract + in-memory implementation
    projection/                # anchor selection + rendering hints
    mcp/                       # transport-independent JSON-RPC gateway
    capability/                # capability policy decisions
    dispatcher/                # explicit human/Agent state machine
    runtime/                   # orchestration + bounded audit state
    ui/                        # browser shell, overlay, HITL surface
  src/commonTest/              # shared state/policy/privacy/serialization evidence
  src/androidMain/             # Android lifecycle and renderer adapters
  src/iosMain/                 # iOS lifecycle and WKWebView adapters
  src/desktopMain/             # Desktop/KCEF lifecycle
  src/wasmJsMain/              # browser entry and web resources

iosApp/                       # Xcode host shell

docs/
  automation/                 # autonomous dual-lane/Shadow/safety binding and repository profile
  architecture/               # hard laws and ADRs
  git/                        # Git Town profile, stack graph, Worker protocol
  harness/                    # eval, Shadow checkpoint, safety and evidence contract
  release/                    # platform delivery runbooks
  security/                   # threat model
  TRACEABILITY.md             # REQ/SM/DF/INV/EVAL/WP/STACK/evidence index

.github/
  ISSUE_TEMPLATE/             # eval-first, safety-bound Stack PR task packet
  PULL_REQUEST_TEMPLATE.md    # branch/path/state/safety/evidence contract
  workflows/                  # CI and Pages workflows
```

## Git Town Stacked PR governance

This repository consumes the canonical [`git-town-stacked-pr-worker`](https://github.com/ed3c/skills-shared/tree/main/skills/git-town-stacked-pr-worker) method. The Skill is not copied into this repository.

```text
Git Town                  branch hierarchy + bounded local synchronization
Consumer repository       profile + task packets + path leases + evals + CI + receipts
GitHub publication gate   exact-HEAD publication admission
Pre-existing policy       optional merge authority, only when explicitly preauthorized
External authority        semantic conflicts, legal acceptance, store/production/settings operations
```

### Admission status

| Lane | State |
|---|---|
| Static `.git-town.toml` reviewed for v24.0.0 | present |
| Exact source release and checksum-manifest identity | recorded |
| Host OS/architecture binary checksum | `ABSENT` |
| Executable provenance/SBOM/legal approval | `ABSENT` / `NOT_EXERCISED` |
| Local linked worktree/lease wrapper | `NOT_IMPLEMENTED` / current session `NOT_EXERCISED` |
| Live dry-run and no-push sync canary | `NOT_EXERCISED` |
| Planted conflict canary | `NOT_EXERCISED` |
| Exact-HEAD publication gate | `NOT_IMPLEMENTED` |
| Repository-preauthorized automatic merge | `ABSENT` |

Until admission is complete, a Worker returns `BLOCKED_POLICY`; it does not install `latest`, run another Git tool as a substitute, or publish manually.

### Planned branch graph

Solid edges are true branch-parent dependencies. The convergence branch is created only after every required sibling head is admitted. Independent children have disjoint path leases.

```mermaid
gitGraph
    commit id: "main"
    branch feat/kmp-agent-browser-foundation
    checkout feat/kmp-agent-browser-foundation
    commit id: "PR-1 executable MVP"
    branch docs/agent-integration-stack-index
    checkout docs/agent-integration-stack-index
    commit id: "issue-6 governance"
    branch build/runtime-dependency-admission
    checkout build/runtime-dependency-admission
    commit id: "issue-7 deps"
    branch feat/persistent-memory
    checkout feat/persistent-memory
    commit id: "issue-8 memory"
    checkout build/runtime-dependency-admission
    branch feat/openclaw-stream-contract
    checkout feat/openclaw-stream-contract
    commit id: "issue-9 edge"
    checkout docs/agent-integration-stack-index
    branch feat/native-capability-contracts
    checkout feat/native-capability-contracts
    commit id: "issue-10 toolmaker"
    branch feat/accessibility-action-executor
    checkout feat/accessibility-action-executor
    commit id: "issue-11 executor"
    checkout docs/agent-integration-stack-index
    branch feat/local-semantic-router-contract
    checkout feat/local-semantic-router-contract
    commit id: "issue-12 semantics"
    branch feat/local-embedding-engine
    checkout feat/local-embedding-engine
    commit id: "issue-13 engine"
```

The complete graph, including release branches and convergence, is maintained in [`docs/git/STACKED_PRS.md`](docs/git/STACKED_PRS.md).

### Atomized Stack PR index

| Order | Issue | Planned branch | Parent | Class | Exclusive path lease | Required evidence / state |
|---:|---:|---|---|---|---|---|
| 0 | #1 | `feat/kmp-agent-browser-foundation` | `main` | foundation | initial repository implementation | Draft PR; CI `PASS` at baseline head |
| 1 | #6 | `docs/agent-integration-stack-index` | foundation | foundation | automation/Git/Harness/root docs/templates | current docs stack; local Git Town `NOT_EXERCISED` |
| 2 | #7 | `build/runtime-dependency-admission` | docs stack | foundation | Gradle catalogs/build files, dependency evidence, NOTICE | exact variants/licenses; no feature code |
| 3A | #8 | `feat/persistent-memory` | runtime deps | child | `persistence/**`, SQLDelight schema/tests, ADR-0004 | migrations/restart/redaction; `NOT_IMPLEMENTED` |
| 3B | #9 | `feat/openclaw-stream-contract` | runtime deps | child | `edge/**` common/mobile tests, ADR-0005 | auth/order/replay/backpressure; `NOT_IMPLEMENTED` |
| 2B | #10 | `feat/native-capability-contracts` | docs stack | sibling | `toolmaker/**`, ADR-0006 | contracts/policy only; `NOT_IMPLEMENTED` |
| 3C | #11 | `feat/accessibility-action-executor` | capability contracts | child | `executor/**` common/platform tests, ADR-0007 | freshness/HITL/preemption; `NOT_IMPLEMENTED` |
| 2C | #12 | `feat/local-semantic-router-contract` | docs stack | sibling | `semantics/**` contract/fixtures, ADR-0008 | deterministic benchmark baseline; `NOT_IMPLEMENTED` |
| 3D | #13 | `feat/local-embedding-engine` | semantic contract | child | semantic engine/platform adapters + its admitted dependency changes | physical-device budget/license; `NOT_IMPLEMENTED` |
| 4A | #2 | `release/android-play-evidence` | action executor | release | Android release workflow/metadata/runbook only | signed AAB + device/pre-launch evidence |
| 4B | #3 | `release/ios-app-store-evidence` | action executor | release | iOS signing/metadata/runbook only | signed archive + TestFlight/device evidence |
| 2D | #5 | `release/web-deployment-evidence` | docs stack | sibling | Pages/Web deployment smoke evidence | deployed URL/browser/CSP receipts |
| 5 | #14 | `converge/release-readiness-index` | docs stack after admitted dependencies | convergence | shared READMEs, `AGENTS.md`, traceability, aggregate release index | full exact-head matrix + external merge/release authority |

Issue #4 remains the parent epic for persistent/private L2 and semantic-runtime work. Leaf PRs must not edit shared indexes; #14 owns final reconciliation.

### Worker synchronization after admission

```bash
# Version-supported equivalents only; exact v24.0.0 binary must be admitted first.
git town sync --stack --dry-run --non-interactive --no-auto-resolve --no-push
git town sync --stack --non-interactive --no-auto-resolve --no-push
```

A command exit `0` proves synchronization only. Publication, CI, merge, store submission, promotion, and rollback remain separate evidence lanes.

## Build and verification

Prerequisites: JDK 17, Android SDK 36 for Android, and Xcode on macOS for iOS.

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:wasmJsBrowserDistribution
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64  # macOS
```

Development entry points:

```bash
./gradlew :composeApp:run
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

See [`docs/harness/README.md`](docs/harness/README.md) for module evals, Shadow checkpoints, mutation controls, safety postconditions, evidence states, and release proof boundaries.

## MCP compatibility boundary

`BrowserMcpGateway` lives in `commonMain`, supports stateless discovery plus the legacy initialization path, and exposes only sanitized resources and typed action proposals. It intentionally starts no network listener. Platform/edge transports must add authenticated pairing, protocol/version negotiation, origin policy, rate limits, cancellation, replay protection, and lifecycle shutdown.

The official Kotlin SDK may be used in edge/platform modules only where its published variants match the admitted target. The common mobile core does not pretend an unavailable artifact variant exists. See [ADR-0003](docs/architecture/ADR-0003-mcp-platform-boundary.md).

## Platform constraints

- Mobile WebViews do not support Chrome extensions; assistant behavior uses controlled observer injection and a JS bridge.
- iOS uses WKWebView; Chromium-only behavior requires a fallback or an explicit unsupported state.
- Web/Wasm remains subject to same-origin, CSP, `X-Frame-Options`, and iframe policy. App-owned pages can expose richer context through an explicit `postMessage` contract; arbitrary sites may refuse embedding.
- Desktop KCEF provides the richest Chromium surface but increases package size, memory use, and cold-start cost.

## Security, privacy, and publication model

No arbitrary model output is executed. Models and remote peers propose typed actions; capability policy and dispatcher state decide whether the proposal is denied, staged, or requires explicit confirmation. Password/payment fields are excluded before Kotlin processing.

Autonomous repository work also preserves repository visibility, owner, access/rulesets, default branch, license/usage-right meaning, private-data boundary, user local state, protected history, and remote topology. The current connector does not prove local user-state preservation, so that lane remains `NOT_EXERCISED` rather than PASS.

Production work still requires identity pinning, attestation, zero-telemetry review, persistent audit evidence, physical-device tests, store privacy declarations, and external authority.

Read [`SECURITY.md`](SECURITY.md), [`docs/security/THREAT_MODEL.md`](docs/security/THREAT_MODEL.md), [`docs/automation/README.md`](docs/automation/README.md), and [`docs/TRACEABILITY.md`](docs/TRACEABILITY.md).

## License

Apache License 2.0. Third-party dependencies retain their own licenses; see [`NOTICE`](NOTICE). This autonomous binding does not alter license or usage rights. Git Town is a development tool and is separately admitted under `docs/git/GIT_TOWN_ADMISSION.md`.
