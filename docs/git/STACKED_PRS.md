# Stacked PR graph and traceability index

This document is the branch/issue/path/eval SSOT for Git Town work. It separates the **actual remote graph** from the **planned implementation graph**. A planned row does not prove that its branch, worktree, local verification, or PR exists.

## Evidence vocabulary

```text
PASS
FAIL
ABSENT
NOT_IMPLEMENTED
NOT_EXERCISED
SKIPPED_BY_POLICY
EXTERNAL_AUTHORITY_REQUIRED
```

## Actual remote graph at the autonomous v2 binding

```text
main @ e447dea815d63e89afd6acf58845f222bee07b6f
└── feat/kmp-agent-browser-foundation @ a449fac24b8ee602b3c36ae60e972fe25f35c516
    └── docs/agent-integration-stack-index @ <current PR #15 head>
```

| Branch | Issue / PR | Remote state | Verification |
|---|---|---|---|
| `main` | default branch | exists | preservation subject; no mutation admitted |
| `feat/kmp-agent-browser-foundation` | PR #1 | open draft, mergeable | CI `PASS` at `a449fac...` |
| `docs/agent-integration-stack-index` | issue #6 / PR #15 | open draft child PR | forge branch/PR lane admitted; local Git Town/worktree `NOT_EXERCISED` |

No other planned branch is created by this document.

## Planned graph

```text
main
└── feat/kmp-agent-browser-foundation                     # PR #1
    └── docs/agent-integration-stack-index                # issue #6 / PR #15
        ├── build/runtime-dependency-admission            # issue #7
        │   ├── feat/persistent-memory                    # issue #8
        │   └── feat/openclaw-stream-contract             # issue #9
        ├── feat/native-capability-contracts              # issue #10
        │   └── feat/accessibility-action-executor        # issue #11
        │       ├── release/android-play-evidence         # issue #2
        │       └── release/ios-app-store-evidence        # issue #3
        ├── feat/local-semantic-router-contract           # issue #12
        │   └── feat/local-embedding-engine               # issue #13
        ├── release/web-deployment-evidence               # issue #5
        └── converge/release-readiness-index              # issue #14, create last
```

`converge/release-readiness-index` has logical dependencies on admitted leaf/release heads but one Git parent. It is created only after required branches are admitted/reparented. It never attempts an automatic multi-parent merge.

Issue #4 is the umbrella epic for private L2, persistence, and semantic-runtime work. Issues #8, #9, #12, and #13 are its atomized execution units.

## Stack index

| Sequence | Stack ID | Issue | Head branch | PR base / parent | Class | Goal | State |
|---:|---|---:|---|---|---|---|---|
| 0 | `STACK-000` | #1 | `feat/kmp-agent-browser-foundation` | `main` | foundation | Executable four-platform Agent-browser MVP | open draft; CI `PASS` |
| 1 | `STACK-006` | #6 | `docs/agent-integration-stack-index` | foundation | foundation | Agent/docs/autonomous dual-lane/Git Town SSOT | open draft PR #15 |
| 2 | `STACK-007` | #7 | `build/runtime-dependency-admission` | docs stack | foundation | Exact SQLDelight/Ktor dependency and legal admission | `NOT_IMPLEMENTED` |
| 3A | `STACK-008` | #8 | `feat/persistent-memory` | runtime deps | child | SQLDelight L1 + append-only audit | `NOT_IMPLEMENTED` |
| 3B | `STACK-009` | #9 | `feat/openclaw-stream-contract` | runtime deps | child | Authenticated ordered OpenClaw L2 transport | `NOT_IMPLEMENTED` |
| 2B | `STACK-010` | #10 | `feat/native-capability-contracts` | docs stack | sibling | Toolmaker contracts and permission mapping | `NOT_IMPLEMENTED` |
| 3C | `STACK-011` | #11 | `feat/accessibility-action-executor` | capability contracts | child | Freshness-bound executor behind HITL | `NOT_IMPLEMENTED` |
| 2C | `STACK-012` | #12 | `feat/local-semantic-router-contract` | docs stack | sibling | Semantic adapter contract and reproducible baseline | `NOT_IMPLEMENTED` |
| 3D | `STACK-013` | #13 | `feat/local-embedding-engine` | semantic contract | child | Select one admitted physical-device engine | `NOT_IMPLEMENTED` |
| 4A | `STACK-002` | #2 | `release/android-play-evidence` | action executor | release | Signed AAB, Play, device evidence | `NOT_EXERCISED` |
| 4B | `STACK-003` | #3 | `release/ios-app-store-evidence` | action executor | release | Signed archive, TestFlight, device evidence | `NOT_EXERCISED` |
| 2D | `STACK-005` | #5 | `release/web-deployment-evidence` | docs stack | sibling | Publish and verify Wasm deployment | `NOT_EXERCISED` |
| 5 | `STACK-014` | #14 | `converge/release-readiness-index` | docs stack after admitted dependencies | convergence | Shared index and release evidence reconciliation | `NOT_IMPLEMENTED` |

## Path leases

Shared files are intentionally excluded from leaf branches. This prevents multiple Workers from rewriting aggregate state.

| Issue | Exclusive writable paths | Named exclusions |
|---:|---|---|
| #6 | `.git-town.toml`, `AGENTS.md`, `README*`, `docs/automation/**`, `docs/git/**`, `docs/harness/**`, `docs/TRACEABILITY.md`, task/PR templates | `composeApp/src/**`, `iosApp/**`, `LICENSE`, `NOTICE`, signing/secret material |
| #7 | Gradle catalogs/build files, `NOTICE`, `docs/dependencies/**` | all feature source directories; `LICENSE`; removal/reinterpretation of existing notices |
| #8 | `persistence/**`, `commonMain/sqldelight/**`, persistence tests, ADR-0004 | build files, `README*`, traceability, edge/tool/executor paths |
| #9 | `edge/**` in common/Android/iOS, edge tests, ADR-0005 | build files, cache/dispatcher/MCP internals, shared indexes |
| #10 | `toolmaker/**`, toolmaker tests, ADR-0006 | platform actuals, dispatcher/MCP internals, shared indexes |
| #11 | `executor/**` in common/platforms, executor tests, ADR-0007 | observer source, cache/MCP internals, shared indexes |
| #12 | `semantics/**` contract/fixtures, semantic eval corpus, ADR-0008 | build files, cache/projection internals, shared indexes |
| #13 | selected engine dependency lines, semantic engine/platform adapters, engine tests, dependency evidence | cache/projection/dispatcher, shared indexes |
| #2 | Android release workflow/config/metadata/runbook and Android-only attestation actual | iOS/Web, shared architecture indexes |
| #3 | iOS signing/config/metadata/runbook and iOS-only attestation actual | Android/Web, shared architecture indexes |
| #5 | Pages workflow/config, Web deployment smoke fixtures/receipts | mobile release paths, shared architecture indexes |
| #14 | `README*`, `AGENTS.md`, `docs/TRACEABILITY.md`, aggregate architecture/release indexes | all implementation source |

A task packet can narrow these paths but cannot broaden them without updating this SSOT through an admitted governance/convergence packet. Sibling overlap produces `BLOCKED_BRANCH_LEASE`.

## Dependency and parallelism rules

- #8 and #9 are sibling children of #7 and may run in parallel because source leases are disjoint.
- #7, #10, #12, and #5 may run in parallel after #6 is admitted; only #7 owns shared build/dependency files at that stage.
- #11 is serial after #10 because it consumes Toolmaker contracts.
- #13 is serial after #12 and owns only the selected semantic-engine dependency changes after the contract baseline exists.
- #2 and #3 are sibling release stacks after #11. They may be reviewed in parallel but cannot claim completion until logical transport/attestation dependencies are admitted.
- #14 is not parallel implementation work. It is created last and owns shared reconciliation only.
- A blocked transition or Worker does not starve independent admitted siblings.

## Per-stack eval index

| Issue | Required positive evals | Required negative controls | Evidence boundary |
|---:|---|---|---|
| #6 | Markdown/link/Mermaid consistency; autonomous/Shadow state and safety profile; exact-head full CI matrix; before/after metadata | local Skill shadow, authority expansion, fake local PASS, visibility/access/license/private-egress mutation, path overlap, config-as-tool-admission, missing merge preauthorization | docs/static/forge policy only; local worktree/Git Town `NOT_EXERCISED` |
| #7 | exact variants on Android/iOS/Desktop/Wasm; full build matrix; license/notices/provenance | dynamic version, missing target, opaque installer, feature code in dependency PR | dependency/build admission only |
| #8 | migrations, restart, ordering, retention, deletion, corruption, redaction | destructive schema without migration; secret fixture persistence | local persistence only |
| #9 | identity, order, duplicate/replay, expiry, cancellation, jitter, reconnect, backpressure | anonymous/wrong-origin/old-sequence/replayed action | transport tests are not production pairing |
| #10 | descriptor, permission, risk, availability, typed result contracts | Tool without descriptor/policy/audit; MCP enabling a Tool | contracts only |
| #11 | page/anchor freshness, HITL receipt, timeout, completion/failure, user preemption | raw selector, coordinate-only click, stale/hidden/ambiguous/sensitive target | fixtures are not arbitrary-site permission |
| #12 | reproducible precision/recall fixtures; latency/memory budget; fallback | vendor selection from prose; score overriding policy | adapter contract/baseline only |
| #13 | same corpus across candidates; physical-device budget; license/variant evidence | simulator-only benchmark, unsupported target, remote leakage | engine compile is not shipping readiness |
| #2 | signed AAB, Play App Signing, device/API matrix, pre-launch, privacy/attestation | debug APK relabeled as release | Google Play lane only |
| #3 | signed archive, Organizer validation, TestFlight, physical devices, privacy/attestation | simulator framework relabeled as release | Apple delivery lane only |
| #5 | deployed HTTPS URL, MIME/cache headers, browser/accessibility/CSP smoke | build artifact relabeled as deployment; origin bypass | Web deployment lane only |
| #14 | fetched ancestry, exact-head full matrix, current receipts, safety postconditions, state vocabulary | old-SHA CI, skipped draft job as PASS, semantic fixes in convergence | aggregate evidence only |

The executable commands, Shadow checkpoints, safety postconditions, and state-machine-specific tests are in `../harness/README.md` and each issue body.

## Branch and delivery lifecycle

After exact local executable/worktree admission:

1. Snapshot immutable repository, local user, legal, ref, remote, and path-lease state.
2. Create/attach the branch in one admitted linked worktree.
3. Record the parent edge before implementation.
4. Run task evals and Shadow checkpoints before publication.
5. Run dry-run no-push stack sync.
6. Run bounded no-push sync.
7. Verify ancestry and safety postconditions independently; rerun evals.
8. Produce an exact-head local verification receipt.
9. Call the publication gate for one allowed intent.
10. Execute at most the returned operation; fetch and verify remote ancestry.
11. Record CI separately.
12. Merge only when repository-owned policy already preauthorizes trusted automation; otherwise record `EXTERNAL_AUTHORITY_REQUIRED`.

```bash
git town sync --stack --dry-run --non-interactive --no-auto-resolve --no-push
git town sync --stack --non-interactive --no-auto-resolve --no-push
```

No live Git Town command or local worktree inventory has been exercised in the current forge-only session. PR #15 is maintained through the existing GitHub review surface without claiming those local lanes.

## Reparent and merge order

The branch graph requires this order; actual merge remains external unless a repository policy later preauthorizes trusted automation:

1. Review PR #1 and PR #15 independently while preserving the declared base relationship.
2. Admit/merge #6 before Worker-controlled leaf stacks.
3. Admit #7 before #8/#9.
4. Admit #10 before #11.
5. Admit #12 before #13.
6. Begin mobile release branches only after required executor/platform/security dependencies are admitted.
7. Web release #5 may proceed independently.
8. Create #14 only after selected leaf/release heads are stable and receipts exist.
9. Reparent children after parent merge through the exact admitted workflow and rerun ancestry evals.
10. Without merge preauthorization, leave PRs open with `EXTERNAL_AUTHORITY_REQUIRED`; do not ask, ship, weaken checks, or change rulesets.

## Rollback and blocked state

Every task packet names an immutable rollback subject. Workers do not run `git town undo` automatically. On conflict, drift, or immutable-boundary failure:

- stop the named transition;
- preserve worktree, branch, conflict state, logs, and receipt;
- avoid semantic edits, continue/skip/reset/force-push, destructive cleanup, or overwrite of user work;
- update the authoritative issue when forge write exists;
- return the stable blocked outcome;
- continue independent safe siblings.

Shared outcomes and receipt requirements are defined in `WORKER_PROTOCOL.md`.
