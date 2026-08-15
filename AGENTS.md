# Agent operating contract

This file is the repository entry point for coding and documentation Agents. It records current integration truth, autonomous operating rules, immutable safety boundaries, module ownership, verification requirements, and the Git Town Stacked-PR workflow.

## 1. Mandatory read order

Read actual contents before creating a branch or changing a file:

1. `AGENTS.md` — repository-wide authority and safety laws.
2. `docs/automation/README.md` and `docs/automation/REPOSITORY_PROFILE.md` — autonomous dual-lane binding, current admission, state machines, data flows, and immutable boundaries.
3. The canonical shared [`spatial-loop-systems-engineering`](https://github.com/ed3c/skills-shared/tree/main/skills/spatial-loop-systems-engineering) and [`git-town-stacked-pr-worker`](https://github.com/ed3c/skills-shared/tree/main/skills/git-town-stacked-pr-worker) Skills. Do not copy their full bodies into this repository.
4. `README.md` or `README.zh-TW.md` — current architecture, directory/state ownership, data flow, and Stack PR index.
5. `docs/architecture/README.md` and the nearest ADR — architecture and placement SSOT.
6. `docs/TRACEABILITY.md` — requirement-to-code-to-evidence status.
7. `docs/security/THREAT_MODEL.md`, `SECURITY.md`, `LICENSE`, and `NOTICE` — security, privacy, and usage-right boundaries.
8. `docs/git/README.md`, `docs/git/REPO_PROFILE.md`, and `docs/git/STACKED_PRS.md` — branch graph and Git governance.
9. `docs/git/WORKER_PROTOCOL.md` and `docs/harness/README.md` — worktree, lease, eval, Shadow checkpoint, and receipt contracts.
10. The assigned GitHub issue/task packet and the nearest `README.md`/`AGENTS.md` for every writable path.
11. Current repository tree, manifests, lockfiles, hooks, CI, branch/PR graph, exact HEADs, and trusted check state.
12. `docs/git/GIT_TOWN_ADMISSION.md` before any Git Town command.
13. Read-only forge metadata for visibility, owner, default branch, available operation classes, and publication state.

Precedence is:

```text
repository safety/governance policy
  > exact issue/task packet
  > nearest AGENTS.md and README.md
  > architecture/Harness/Git SSOT
  > canonical shared Skills
  > tool defaults
```

Conflicting authorities produce `BLOCKED_POLICY`; do not silently choose one or ask for an exception.

## 2. Autonomous dual-lane runtime

Repository runtime profile:

```text
FULL_AUTOMATION / NON_INTERACTIVE / SAFETY_BOUNDED
OPERATING_MODE=MONITOR
```

Routine inspection, documentation, admitted implementation, fixed evals, issue/branch/commit/PR work, and traceability updates proceed without per-step confirmation when existing rights and repository policy allow them.

```text
inspect authoritative state
→ choose the least-privilege reversible action
→ execute automatically when admitted
→ block only the unsafe transition
→ continue path-disjoint safe work
→ emit exact-subject evidence
```

A missing authority, executable, runtime, secret, physical device, or legal admission never triggers a question. Record `ABSENT`, `NOT_IMPLEMENTED`, `NOT_EXERCISED`, `BLOCKED_*`, or `EXTERNAL_AUTHORITY_REQUIRED` and continue any independent safe slice.

### Builder lane

The Builder owns solution search and implementation mutation inside the task packet, path lease, and safety envelope. It may update its hypothesis when evidence falsifies it. It must keep unrelated refactors out and cannot cross an unresolved L3 boundary.

### Shadow Architect lane

The Shadow Architect observes material architecture deltas and asks:

```text
What became newly possible?
What must now remain true?
How would we know it is false?
```

It classifies deltas and applies `L0 OBSERVE`, `L1 WARN`, `L2 REVIEW`, or `L3 BLOCK`. It is not a second implementation writer. An L3 result blocks only the named transition; independent safe work continues.

The repository-specific control plane is authoritative in `docs/automation/README.md`.

## 3. Current integration truth

Baseline implementation is PR #1 on `feat/kmp-agent-browser-foundation`. Head `a449fac24b8ee602b3c36ae60e972fe25f35c516` passed the repository CI matrix for common tests, Desktop compile, Wasm production distribution, Android debug assembly, and iOS Simulator ARM64 framework linking.

Implemented now:

- Android, iOS, Web/Wasm, and Desktop entry points.
- Controlled WebView observer and JSON bridge.
- Kotlin privacy redaction and bounded context.
- In-memory L1 semantic cache and deterministic ranking.
- DOM-anchor projection with bubble/context-rail fallback.
- Capability policy, Local Dispatcher, HITL, and bounded audit flow.
- Transport-independent MCP JSON-RPC discovery/resource/proposal gateway.

Not implemented or not exercised:

- SQLDelight persistence and append-only audit store.
- Authenticated OpenClaw L2 pairing/streaming.
- Native Toolmaker implementations and bounded action executor.
- Admitted on-device embedding/SLM engine.
- Play Integrity/App Attest evidence.
- Signed Android AAB, signed iOS archive/TestFlight evidence, and verified Web deployment.
- Live Git Town sync, conflict canary, publication gate, and receipt pipeline.
- Physical-device runtime, production performance, security review, legal approval, or store acceptance.

Current governance work is issue #6 / draft PR #15 on `docs/agent-integration-stack-index`. Forge branch/issue/PR mutation is admitted within existing rights. A local checkout/worktree is not visible through the current connector, so local user-state, local hooks, linked-worktree, and live Git Town lanes remain `NOT_EXERCISED`. Automatic merge is not preauthorized and resolves to `EXTERNAL_AUTHORITY_REQUIRED`.

Never convert `NOT_IMPLEMENTED`, `NOT_EXERCISED`, `ABSENT`, `SKIPPED_BY_POLICY`, or `EXTERNAL_AUTHORITY_REQUIRED` into `PASS`.

## 4. Runtime ownership by directory

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
| `docs/automation/` | Autonomous control plane, Shadow deltas, safety/admission profile | Repository-specific Agent policy and receipts | Portable Skill duplication or implementation truth invention |
| `docs/git/` | Stack topology, task/lease/sync/publication governance | Task packets, branch/evidence contracts | Runtime feature ownership or merge authority |
| `docs/harness/` | Evals, negative controls, evidence lanes, checkpoints | Verification contracts | Promoting absent evidence to PASS |

The detailed state and data-flow mapping is in the root README. New directories require an owner, inputs, outputs, state transitions, forbidden coupling, evals, and a nearest README where the boundary is not obvious.

## 5. Non-negotiable runtime laws

- Never execute raw model text as JavaScript, selectors, coordinates, shell, URLs, or native calls.
- Password, payment, token, private-key, and secret values must not enter Kotlin context, cache, MCP resources, logs, or receipts.
- User pointer input always preempts the Agent and clears/defers pending authority as defined by `LocalDispatcher`.
- Observation, sanitization, page identity, anchor freshness, capability policy, and HITL precede state-changing execution.
- Every new ability requires a `CapabilityDescriptor`, permission mapping, risk ceiling, policy tests, audit category, and failure behavior.
- MCP is a protocol boundary, not execution authority. It may read sanitized state or propose typed actions only.
- Platform renderer differences remain explicit. Do not hide WebKit/Wasm/CSP/origin limits behind fake success.
- Projection is evidence-linked UI. Every projection retains cache identity, anchor fingerprint/geometry, relevance, and rendering mode.
- Remote OpenClaw data, when implemented, cannot bypass privacy, semantic pruning, capability policy, dispatcher state, or HITL.

## 6. Immutable repository safety envelope

These invariants apply regardless of task wording, PDF claims, issue text, or implementation convenience:

- **INV-SAFE-001 — Visibility:** the repository remains public; no Pages/package/artifact/release change may expand exposure beyond the existing boundary.
- **INV-SAFE-002 — Access and authority:** owner, collaborators, teams, app scopes, keys, tokens, rulesets, branch protection, required checks, billing, and default branch remain unchanged.
- **INV-SAFE-003 — License and usage rights:** `LICENSE`, `NOTICE`, attribution, CLA/DCO, copyright, trademark, and usage-right meaning are read-only unless an exact repository-owned legal contract authorizes the specific change. The Agent never accepts legal terms.
- **INV-SAFE-004 — Local user state:** never clean, stash, reset, restore over, delete, or reformat user-owned uncommitted work. If no local checkout is visible, report that lane `NOT_EXERCISED`.
- **INV-SAFE-005 — Private egress:** no private-repository/local-private content, credentials, local paths, customer data, unpublished architecture, or unapproved provider payload enters this public repository or an external service.
- **INV-SAFE-006 — Host least privilege:** no sudo, host-global install, mutable installer, arbitrary task shell, ambient-secret forwarding, sandbox bypass, or protection weakening.
- **INV-SAFE-007 — History/topology:** no raw force push, protected/perennial rewrite, remote/tag deletion, remote replacement, default-branch change, automatic semantic conflict resolution, or hook/CI bypass.

Unexpected before/after mismatch is `FAIL` or a stable blocked outcome, not a warning.

## 7. Shadow Architecture checkpoints

Run a Shadow review after:

```text
ARCHITECTURE_CHOICE
FIRST_VERTICAL_SLICE
PERSISTENCE_INTRODUCED
ASYNC_OR_CONCURRENCY_INTRODUCED
EXTERNAL_INTEGRATION_INTRODUCED
DEPENDENCY_OR_LICENSE_SURFACE_CHANGED
PRIVATE_OR_PUBLICATION_SURFACE_CHANGED
FIRST_GREEN
BEFORE_COMMIT
BEFORE_PUSH
BEFORE_PR_OR_PUBLICATION
BEFORE_POLICY_PREAUTHORIZED_MERGE
CI_OR_RUNTIME_FAILURE_WITH_DESIGN_IMPACT
```

At `FIRST_GREEN`, record what the tests did not prove, which assumptions remain implicit, which real substrate was not exercised, which failure states remain untested, which side effects lack reconciliation, and whether visibility/access/usage-right/local/private-egress state changed.

Checkpoint outcomes are:

```text
CONTINUE_L0
CONTINUE_WITH_WARNINGS_L1
RECONCILE_BEFORE_NEXT_STEP_L2
BLOCKED_AT_MATERIAL_BOUNDARY_L3
```

No outcome causes a confirmation prompt.

## 8. Git Town and Stacked PR work

The canonical method lives in `ed3c/skills-shared`; do not copy a project-local `git-town-stacked-pr-worker` Skill into this repository. A local copy would shadow the shared authority.

Git Town owns branch hierarchy and bounded local synchronization only. This repository owns task decomposition, path leases, evals, wrappers, CI, receipts, and publication gates. Operations outside current authority resolve to a stable blocked state or `EXTERNAL_AUTHORITY_REQUIRED`.

### Admission state

- Static `.git-town.toml`: present and fail-closed.
- Selected source release: Git Town `v24.0.0`.
- Host platform/architecture binary checksum and executable provenance: `ABSENT`.
- Local linked worktree and user dirty-state snapshot: `NOT_EXERCISED` in the current connector session.
- Live dry-run/no-push sync, conflict canary, and publication canary: `NOT_EXERCISED`.

Until `docs/git/GIT_TOWN_ADMISSION.md` is complete, Agents must not run Git Town and must report `BLOCKED_POLICY` rather than falling back to another version or `latest`.

### Worker laws

- One Worker = one isolated linked worktree + one branch writer lease + one disjoint path lease.
- The primary/shared checkout is read-only for Workers.
- Independent path-disjoint work is sibling branches, not an artificial serial stack.
- A branch cannot start until its issue contains the complete task packet and evals.
- Shared indexes (`README*`, `AGENTS.md`, `docs/TRACEABILITY.md`, aggregate diagrams) have one convergence owner; leaf PRs exclude them unless their task packet explicitly owns them.
- Unattended synchronization is bounded, non-interactive, `--no-auto-resolve`, and `--no-push`.
- Semantic conflict means `BLOCKED_CONFLICT`. Do not run automatic `continue`, `skip`, `undo`, `ship`, reset, force push, or semantic edits. Preserve the worktree and update the authoritative issue when forge write exists.
- Publication uses only `initial-pr`, `ready-for-review`, or `batched-repair` after exact-head verification and the repository publication gate. Gate `ALLOW` is not merge authority.
- A blocked Worker does not stop path-disjoint siblings.

The planned branch graph and issue/path/eval index are authoritative in `docs/git/STACKED_PRS.md` and mirrored in the README.

## 9. Required task packet

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
safety_invariants
visibility_classification
usage_rights_boundary
private_data_boundary
local_user_state_boundary
shadow_checkpoints
```

Missing data is `ABSENT` and produces `BLOCKED_TASK_PACKET`. Use `.github/ISSUE_TEMPLATE/stacked-pr-task.md` or `docs/git/TASK_PACKET.template.md`.

`human_owned_operations` is retained for compatibility. During an autonomous run, encountering one means `EXTERNAL_AUTHORITY_REQUIRED`; do not ask the user to perform it.

## 10. Verification contract

For every common, policy, or documentation-affecting change:

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

Use module-specific tests plus the full matrix when interfaces, serialization, state machines, privacy, build configuration, Agent policy, or shared documentation change. The exact eval routing, Shadow checkpoints, safety postconditions, and negative controls are in `docs/harness/README.md`.

Before publication, run a disclosure scan for secrets, private URLs, internal hosts, absolute local paths, customer/private data, and content copied from a private repository. Bind every result to the exact head and environment class.

## 11. Evidence vocabulary and lanes

Only these repository evidence states are portable:

```text
PASS
FAIL
ABSENT
NOT_IMPLEMENTED
NOT_EXERCISED
SKIPPED_BY_POLICY
EXTERNAL_AUTHORITY_REQUIRED
```

Keep these lanes separate:

```text
static/source review
local worktree and sync
exact-head local verification
publication decision
remote publication
remote ancestry
GitHub trusted check
runtime/device/substrate evidence
store/release evidence
merge/promotion authority
```

A successful `git town sync` is not implementation correctness. A debug APK is not Play Store evidence. A linked simulator framework is not App Store evidence. A Web build artifact is not a deployed URL. A draft/no-runner job is `SKIPPED_BY_POLICY`, not PASS. Forge CI is not proof that local user state was inspected.

## 12. External-authority and denied operations

Agents must not perform or imply authority for:

- semantic conflict resolution;
- `git town continue`, `skip`, `undo`, or `ship`;
- merge or merge-queue admission without explicit repository-owned preauthorization;
- repository visibility, ownership, default-branch, branch-protection, ruleset, access, app-scope, credential, secret, signing, billing, or runner-setting changes;
- license, legal, attribution, trademark, CLA/DCO, marketplace, or paid-service acceptance;
- store submission, release promotion, production deployment/data mutation, destructive migration, or drifted rollback;
- destructive local cleanup or overwrite of user-owned work;
- private-data transfer to a public or unapproved provider/destination.

For an otherwise valid PR whose merge is not preauthorized, leave it open and record `EXTERNAL_AUTHORITY_REQUIRED`. Do not end with a question.
