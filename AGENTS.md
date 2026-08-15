# Agent operating contract

This file is the repository entry point for coding and documentation agents. It records current integration truth, authority boundaries, module ownership, verification requirements, and the Git Town Stacked-PR workflow.

## 1. Mandatory read order

Read these documents before creating a branch or changing a file:

1. `AGENTS.md` — repository-wide authority and safety laws.
2. `README.md` or `README.zh-TW.md` — current architecture, directory/state ownership, data flow, and stack index.
3. `docs/architecture/README.md` and the nearest ADR — architecture and placement SSOT.
4. `docs/TRACEABILITY.md` — requirement-to-code-to-evidence status.
5. `docs/security/THREAT_MODEL.md` — privacy and execution boundaries.
6. `docs/git/README.md`, `docs/git/REPO_PROFILE.md`, and `docs/git/STACKED_PRS.md` — branch graph and Git governance.
7. `docs/git/WORKER_PROTOCOL.md` and `docs/harness/README.md` — worktree, lease, eval, and receipt contracts.
8. The assigned GitHub issue/task packet and the nearest `README.md` for every writable path.
9. `docs/git/GIT_TOWN_ADMISSION.md` and the shared [`git-town-stacked-pr-worker`](https://github.com/ed3c/skills-shared/tree/main/skills/git-town-stacked-pr-worker) Skill before any Git Town command.
10. Current local/remote branch graph, PR bases, exact HEADs, and trusted check state.

Precedence is:

```text
repository policy
  > issue/task packet
  > shared Skill
  > tool defaults
```

Conflicting authorities produce `BLOCKED_POLICY`; do not silently choose one.

## 2. Current integration truth

Baseline implementation is PR #1 on `feat/kmp-agent-browser-foundation`. Head `a449fac24b8ee602b3c36ae60e972fe25f35c516` passed the repository CI matrix for common tests, Desktop compile, Wasm production distribution, Android debug assembly, and iOS Simulator ARM64 framework linking.

Implemented now:

- Android, iOS, Web/Wasm, and Desktop entry points.
- Controlled WebView observer and JSON bridge.
- Kotlin privacy redaction and bounded context.
- In-memory L1 semantic cache and deterministic ranking.
- DOM-anchor projection with bubble/context-rail fallback.
- Capability policy, Local Dispatcher, HITL, audit flow.
- Transport-independent MCP JSON-RPC discovery/resource/proposal gateway.

Not implemented or not exercised:

- SQLDelight persistence and append-only audit store.
- Authenticated OpenClaw L2 pairing/streaming.
- Native Toolmaker implementations and bounded action executor.
- Admitted on-device embedding/SLM engine.
- Play Integrity/App Attest evidence.
- Signed Android AAB, signed iOS archive/TestFlight evidence, and verified Web deployment.
- Live Git Town sync, conflict canary, publication gate, and receipt pipeline.

Never convert `NOT_IMPLEMENTED`, `NOT_EXERCISED`, `ABSENT`, or `SKIPPED_BY_POLICY` into `PASS`.

## 3. Runtime ownership by directory

| Path | Owns | May emit | Must not own |
|---|---|---|---|
| `domain/` | Serializable contracts and stable identifiers | Immutable DTOs | I/O, policy decisions, platform APIs |
| `web/` | Observer injection and JS-message decoding | Raw `PageContext` | Privileged actions, secrets, authorization |
| `privacy/` | Filtering, redaction, size/element bounds | Sanitized `PageContext` | Cache ranking, action permission |
| `cache/` | L1 query/put/remove/clear semantics | `CacheMatch` | UI rendering, network transport |
| `projection/` | Match-to-anchor selection and rendering hints | `ProjectionHint` | Action execution or authorization |
| `mcp/` | JSON-RPC validation, discovery, sanitized resources, typed proposals | Resource/tool results or `AgentAction` proposal | Network identity, direct WebView/native execution |
| `capability/` | Registered ability, enablement, permission and risk ceilings | `Allowed`, `RequiresConfirmation`, `Denied` | Temporal execution authority |
| `dispatcher/` | Human/agent temporal authority and preemption | `DispatcherSnapshot` | Capability registration or UI rendering |
| `runtime/` | Pipeline orchestration and bounded audit state | Context/projection/audit flows | Platform lifecycle and store packaging |
| `ui/` | Rendering, pointer preemption signals, HITL choices | Confirm/reject/user-interaction events | Policy invention or hidden execution |
| `androidMain/`, `iosMain/`, `desktopMain/`, `wasmJsMain/` | Renderer lifecycle, platform adapters, packaging | Platform events/results | Shared policy divergence |
| `iosApp/` | Xcode host and Apple delivery shell | Native app lifecycle | Common business/policy logic |

The detailed state and data-flow mapping is in the root README. New directories require an owner, inputs, outputs, state transitions, forbidden coupling, evals, and a nearest README where the boundary is not obvious.

## 4. Non-negotiable runtime laws

- Never execute raw model text as JavaScript, selectors, coordinates, shell, URLs, or native calls.
- Password, payment, token, private-key, and secret values must not enter Kotlin context, cache, MCP resources, logs, or receipts.
- User pointer input always preempts the agent and clears/defer pending authority as defined by `LocalDispatcher`.
- Observation, sanitization, page identity, anchor freshness, capability policy, and HITL precede state-changing execution.
- Every new ability requires a `CapabilityDescriptor`, permission mapping, risk ceiling, policy tests, audit category, and failure behavior.
- MCP is a protocol boundary, not execution authority. It may read sanitized state or propose typed actions only.
- Platform renderer differences remain explicit. Do not hide WebKit/Wasm/CSP/origin limits behind fake success.
- Projection is evidence-linked UI. Every projection retains cache identity, anchor fingerprint/geometry, relevance, and rendering mode.
- Remote OpenClaw data, when implemented, cannot bypass privacy, semantic pruning, capability policy, dispatcher state, or HITL.

## 5. Git Town and Stacked PR work

The canonical method lives in `ed3c/skills-shared`; do not copy a project-local `git-town-stacked-pr-worker` Skill into this repository. A local copy would shadow the shared authority.

Git Town owns branch hierarchy and bounded local synchronization only. This repository owns task decomposition, path leases, evals, wrappers, CI, and receipts. Human/trusted operators own semantic conflict resolution, legal acceptance, merge/ship, permission changes, release promotion, and rollback.

### Admission state

- Static `.git-town.toml`: present and fail-closed.
- Selected source release: Git Town `v24.0.0`.
- Host platform/architecture binary checksum and executable provenance: `ABSENT`.
- Live dry-run/no-push sync, conflict canary, and publication canary: `NOT_EXERCISED`.

Until `docs/git/GIT_TOWN_ADMISSION.md` is complete, agents must not run Git Town and must report `BLOCKED_POLICY` rather than falling back to another version or `latest`.

### Worker laws

- One Worker = one isolated linked worktree + one branch writer lease.
- The primary/shared checkout is read-only for Workers.
- Independent path-disjoint work is sibling branches, not an artificial serial stack.
- A branch cannot start until its issue contains the complete task packet and evals.
- Shared indexes (`README*`, `AGENTS.md`, `docs/TRACEABILITY.md`, aggregate diagrams) have one convergence owner; leaf PRs exclude them unless their task packet explicitly owns them.
- Unattended synchronization is bounded, non-interactive, `--no-auto-resolve`, and `--no-push`.
- Semantic conflict means `BLOCKED_CONFLICT`. Do not run automatic `continue`, `skip`, `undo`, `ship`, reset, force push, or semantic edits.
- Publication uses only `initial-pr`, `ready-for-review`, or `batched-repair` after exact-HEAD local verification and the repository publication gate. Gate `ALLOW` is not merge authority.

The planned branch graph and issue/path/eval index are authoritative in `docs/git/STACKED_PRS.md` and mirrored in the README.

## 6. Required task packet

Every implementation issue must declare:

```text
issue_id
parent_issue_id or NONE
goal
non_goals
base_branch
parent_branch
head_branch
stack_class
allowed_paths
excluded_paths
dependencies
parallel_safe_siblings
required_evals
negative_or_mutation_controls
evidence_boundary
cleanup_contract
rollback_subject
human_owned_operations
```

Missing data is `ABSENT` and produces `BLOCKED_TASK_PACKET`. Use `.github/ISSUE_TEMPLATE/stacked-pr-task.md` or `docs/git/TASK_PACKET.template.md`.

## 7. Verification contract

For every common or documentation-affecting implementation change:

```bash
./gradlew :composeApp:allTests
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:wasmJsBrowserDistribution
./gradlew :composeApp:assembleDebug
```

On macOS also run:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

Use module-specific tests plus the full matrix when interfaces, serialization, state machines, privacy, build configuration, or shared documentation change. The exact eval routing and negative controls are in `docs/harness/README.md`.

## 8. Evidence vocabulary

Only these evidence states are portable:

```text
PASS
FAIL
ABSENT
NOT_IMPLEMENTED
NOT_EXERCISED
SKIPPED_BY_POLICY
```

Keep these lanes separate:

```text
local sync
local verification
publication decision
remote publication
remote ancestry
GitHub trusted check
store/device/release evidence
Human Admit
```

A successful `git town sync` is not implementation correctness. A debug APK is not Play Store evidence. A linked simulator framework is not App Store evidence. A Web build artifact is not a deployed URL. A draft/no-runner job is `SKIPPED_BY_POLICY`, not PASS.

## 9. Human-owned operations

Agents must not perform or imply authority for:

- semantic conflict resolution;
- `git town continue`, `skip`, `undo`, or `ship`;
- merge queue admission or merge;
- branch protection, permissions, credentials, secrets, signing identities, or billing changes;
- license/legal acceptance;
- store submission, release promotion, production deployment, destructive migration, or drifted rollback.

Preserve blocked worktrees and subject-bound evidence for review.
