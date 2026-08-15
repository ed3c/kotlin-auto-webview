# Stacked PR graph and traceability index

This document is the branch/issue/path/eval SSOT for Git Town work. It separates the **actual remote graph** from the **planned implementation graph**. A planned row does not prove that its branch or PR exists.

## Evidence vocabulary

```text
PASS
FAIL
ABSENT
NOT_IMPLEMENTED
NOT_EXERCISED
SKIPPED_BY_POLICY
```

## Actual graph at adoption

```text
main @ e447dea815d63e89afd6acf58845f222bee07b6f
└── feat/kmp-agent-browser-foundation @ a449fac24b8ee602b3c36ae60e972fe25f35c516
    └── docs/agent-integration-stack-index @ <this documentation commit>
```

| Branch | Issue / PR | Remote state | Verification |
|---|---|---|---|
| `main` | default branch | exists | base subject only |
| `feat/kmp-agent-browser-foundation` | PR #1 | open draft, mergeable | CI `PASS` at `a449fac...` |
| `docs/agent-integration-stack-index` | issue #6 / PR `PR_TBD` | current documentation child | Git Town live sync `NOT_EXERCISED` |

No other planned branch is created by this document.

## Planned graph

```text
main
└── feat/kmp-agent-browser-foundation                     # PR #1
    └── docs/agent-integration-stack-index                # issue #6 / PR_TBD
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

`converge/release-readiness-index` has a logical dependency on every admitted leaf/release head but a single Git parent. It is created only after the required branches are merged/reparented according to Human Admit. It does not attempt a multi-parent merge automatically.

Issue #4 is the umbrella epic for private L2, persistence, and semantic-runtime work. Issues #8, #9, #12, and #13 are its atomized execution units.

## Stack index

| Sequence | Issue | Head branch | PR base / parent | Class | Goal | State |
|---:|---:|---|---|---|---|---|
| 0 | #1 | `feat/kmp-agent-browser-foundation` | `main` | foundation | Executable four-platform agent-browser MVP | open draft; CI `PASS` |
| 1 | #6 | `docs/agent-integration-stack-index` | foundation | foundation | Agent/docs/Git Town SSOT | current; PR `PR_TBD` |
| 2 | #7 | `build/runtime-dependency-admission` | docs stack | foundation | Exact SQLDelight/Ktor dependency and legal admission | `NOT_IMPLEMENTED` |
| 3A | #8 | `feat/persistent-memory` | runtime deps | child | SQLDelight L1 + append-only audit | `NOT_IMPLEMENTED` |
| 3B | #9 | `feat/openclaw-stream-contract` | runtime deps | child | Authenticated ordered OpenClaw L2 transport | `NOT_IMPLEMENTED` |
| 2B | #10 | `feat/native-capability-contracts` | docs stack | sibling | Toolmaker contracts and permission mapping | `NOT_IMPLEMENTED` |
| 3C | #11 | `feat/accessibility-action-executor` | capability contracts | child | Freshness-bound executor behind HITL | `NOT_IMPLEMENTED` |
| 2C | #12 | `feat/local-semantic-router-contract` | docs stack | sibling | Semantic adapter contract and reproducible baseline | `NOT_IMPLEMENTED` |
| 3D | #13 | `feat/local-embedding-engine` | semantic contract | child | Select one admitted physical-device engine | `NOT_IMPLEMENTED` |
| 4A | #2 | `release/android-play-evidence` | action executor | release | Signed AAB, Play, device evidence | `NOT_EXERCISED` |
| 4B | #3 | `release/ios-app-store-evidence` | action executor | release | Signed archive, TestFlight, device evidence | `NOT_EXERCISED` |
| 2D | #5 | `release/web-deployment-evidence` | docs stack | sibling | Publish and verify Wasm deployment | `NOT_EXERCISED` |
| 5 | #14 | `converge/release-readiness-index` | docs stack after admitted dependencies | convergence | Shared index and release evidence reconciliation | `NOT_IMPLEMENTED` |

## Path leases

Shared files are intentionally excluded from leaf branches. This prevents multiple Workers from rewriting the same narrative/aggregate state.

| Issue | Exclusive writable paths | Named exclusions |
|---:|---|---|
| #6 | `.git-town.toml`, `AGENTS.md`, `README*`, `docs/git/**`, `docs/harness/**`, `docs/TRACEABILITY.md`, templates | `composeApp/src/**`, `iosApp/**` |
| #7 | Gradle catalogs/build files, `NOTICE`, `docs/dependencies/**` | all feature source directories |
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

A task packet can narrow these paths but cannot broaden them without updating this SSOT and obtaining Human Admit. Sibling overlap produces `BLOCKED_BRANCH_LEASE`.

## Dependency and parallelism rules

- #8 and #9 are sibling children of #7 and may run in parallel because their source path leases are disjoint.
- #7, #10, and #12 may run in parallel after #6 is admitted; only #7 owns shared build files at that stage.
- #11 is serial after #10 because it consumes Toolmaker contracts.
- #13 is serial after #12 and owns only the selected semantic-engine dependency changes after the contract baseline exists.
- #2 and #3 are sibling release stacks after #11. They can be reviewed in parallel but cannot claim completion until their additional logical dependencies (for example authenticated transport or attestation requirements) are admitted.
- #5 is independent of mobile release paths.
- #14 is not parallel implementation work. It is created last and owns shared reconciliation only.

## Per-stack eval index

| Issue | Required positive evals | Required negative controls | Evidence boundary |
|---:|---|---|---|
| #6 | Markdown/link/graph consistency; profile has no silent claims | local Skill shadow, fake PASS, path overlap, config-as-admission | docs/static policy only |
| #7 | exact variants on Android/iOS/Desktop/Wasm; full build matrix; license/notices | dynamic version, missing target, feature code in dependency PR | dependency/build admission only |
| #8 | migrations, restart, ordering, retention, deletion, corruption, redaction | destructive schema without migration; secret fixture persistence | local persistence only |
| #9 | identity, order, duplicate/replay, expiry, cancellation, jitter, reconnect, backpressure | anonymous/wrong-origin/old-sequence/replayed action | transport tests are not production pairing |
| #10 | descriptor, permission, risk, availability, typed result contracts | Tool without descriptor/policy/audit; MCP enabling a Tool | contracts only |
| #11 | page/anchor freshness, HITL receipt, timeout, completion/failure, user preemption | raw selector, coordinate-only click, stale/hidden/ambiguous/sensitive target | fixtures are not arbitrary-site permission |
| #12 | reproducible precision/recall fixtures; latency/memory budget; fallback | vendor selection from prose; score overriding policy | adapter contract/baseline only |
| #13 | same corpus across candidates; physical-device budget; license/variant evidence | simulator-only benchmark, unsupported target, remote leakage | engine compile is not shipping readiness |
| #2 | signed AAB, Play App Signing, device/API matrix, pre-launch, privacy/attestation | debug APK relabeled as release | Google Play lane only |
| #3 | signed archive, Organizer validation, TestFlight, physical devices, privacy/attestation | simulator framework relabeled as release | Apple delivery lane only |
| #5 | deployed HTTPS URL, MIME/cache headers, browser/accessibility/CSP smoke | build artifact relabeled as deployment; origin bypass | Web deployment lane only |
| #14 | fetched ancestry, exact-head full matrix, current receipt links, state vocabulary | old-SHA CI, skipped draft job as PASS, semantic fixes in convergence | aggregate evidence only |

The executable commands and state-machine-specific tests are in `../harness/README.md` and each issue body.

## Git Town branch lifecycle

After exact executable admission:

1. Create/attach the branch in one admitted linked worktree.
2. Record the parent edge before implementation.
3. Run task evals before publication.
4. Run a dry-run no-push stack sync.
5. Run bounded no-push sync.
6. Verify ancestry independently and rerun evals.
7. Produce an exact-head local verification receipt.
8. Call the publication gate for one allowed intent.
9. Execute at most the returned operation and verify fetched remote ancestry.
10. Keep CI/trusted-check and Human Admit separate.

```bash
git town sync --stack --dry-run --non-interactive --no-auto-resolve --no-push
git town sync --stack --non-interactive --no-auto-resolve --no-push
```

No live Git Town command has been exercised in this repository at the time of this document. The current documentation PR was created as an explicit child PR through GitHub while admission remains blocked.

## Reparent and merge order

Human Admit controls each transition:

1. Review PR #1 and the documentation child independently but preserve the declared base relationship.
2. Admit/merge #6 before starting Worker-controlled leaf stacks.
3. Admit #7 before #8/#9.
4. Admit #10 before #11.
5. Admit #12 before #13.
6. Mobile release branches begin only after the required executor/platform/security dependencies are admitted.
7. Web release #5 may proceed independently.
8. Create #14 only after the selected leaf/release heads are stable and their receipts are available.
9. Reparent children after parent merge using the exact admitted Git Town workflow or reviewed GitHub base update; rerun ancestry evals.
10. Merge/ship and branch deletion remain human operations.

## Rollback and blocked state

Every task packet names an immutable rollback subject. Workers do not run `git town undo` automatically. On conflict or drift:

- stop;
- preserve the worktree, branch, conflict state, logs, and receipt;
- avoid semantic edits, continue/skip/reset/force-push;
- return the stable blocked outcome;
- require a human or dedicated recovery issue.

Shared stable outcomes are defined in `WORKER_PROTOCOL.md`.
