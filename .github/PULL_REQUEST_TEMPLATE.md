## Autonomous Stack contract

- Issue / parent issue:
- Goal:
- Non-goals:
- Base branch:
- Parent branch:
- Head branch:
- Exact head / tree:
- Stack class: `foundation | child | sibling | convergence | release | hotfix`
- Dependencies:
- Parallel-safe siblings:
- Rollback subject (immutable SHA/tag):
- Primary autonomous outcome: `AUTOMATED_PR_OPEN | AUTOMATED_PUSHED | AUTOMATED_LOCAL_COMPLETE | PARTIAL_SAFE_COMPLETION | BLOCKED_* | FAILED_*`

## Admission and operating mode

- Runtime profile: `FULL_AUTOMATION / NON_INTERACTIVE / SAFETY_BOUNDED`
- Operating mode: `MONITOR | PRECHECK | POSTMORTEM`
- Complexity: `A | B | C | D`
- Mutation admission: `READ_ONLY_ADMITTED | LOCAL_WORKTREE_ADMITTED | BRANCH_WRITE_ADMITTED | REMOTE_PR_ADMITTED | POLICY_PREAUTHORIZED_MERGE_ADMITTED`
- Implementation gate: `BLOCKED | READY_FOR_PROTOTYPE | READY_FOR_IMPLEMENTATION`
- [ ] Missing authority/evidence was recorded without asking for expansion.
- [ ] A blocked transition did not stop independent safe work.

## Path lease

- Allowed paths:
- Named exclusions:
- Shared-index owner:
- [ ] No active sibling Worker owns an overlapping path.
- [ ] Legal, secret/signing, private-data, and user-local paths remain excluded unless exactly admitted.

## State and data ownership

- Requirements changed (`REQ-*`):
- State machines changed (`SM-*`):
- Data flows changed (`DF-*`):
- Invariants changed (`INV-*`):
- Unknowns/probes changed (`UNK-*`):
- Runtime state machine or pipeline changed:
- Input contract:
- Output contract:
- Forbidden coupling preserved:
- Platforms affected:

## Shadow Architecture ledger

| Checkpoint | Delta class | Newly possible | Must remain true | Falsifier / oracle | Outcome |
|---|---|---|---|---|---|
| `ARCHITECTURE_CHOICE` | | | | | `L0/L1/L2/L3` |
| `FIRST_GREEN` | | | | | `L0/L1/L2/L3` |
| `BEFORE_COMMIT` | | | | | `L0/L1/L2/L3` |
| `BEFORE_PUSH` | | | | | `L0/L1/L2/L3` |
| `BEFORE_PR_OR_PUBLICATION` | | | | | `L0/L1/L2/L3` |

Add persistence, concurrency, external integration, dependency/license, publication, or failure checkpoints when applicable.

## Immutable safety envelope

| Invariant | Before / after result |
|---|---|
| `INV-SAFE-001` repository visibility unchanged | |
| `INV-SAFE-002` owner/default branch/access/rulesets/protection/secrets unchanged | |
| `INV-SAFE-003` license/usage-right/attribution state unchanged | |
| `INV-SAFE-004` user local state preserved, or exact lane `NOT_EXERCISED` | |
| `INV-SAFE-005` private-data egress none; disclosure scan | |
| `INV-SAFE-006` host execution least privilege | |
| `INV-SAFE-007` perennial/protected refs and remote topology unchanged | |

- [ ] No repository settings, visibility, ownership, access, rulesets, secrets, default branch, or legal-policy mutation occurred.
- [ ] No private repository/local-private content, absolute secret path, customer data, or credential-bearing URL was published.
- [ ] No destructive cleanup, auto-stash, hard reset, force push, conflict auto-resolution, hook bypass, or check weakening occurred.

## Security / privacy / usage rights

- Capability/policy impact:
- Sensitive data impact:
- HITL / user-preemption impact:
- Network/identity/origin impact:
- Dependency/model/dataset/media identity and license evidence:
- Required NOTICE/SBOM impact:
- [ ] Raw model output is not executed.
- [ ] Password/payment/secret values cannot enter context, cache, MCP, logs, or receipts.
- [ ] `LICENSE` meaning and existing attribution remain unchanged.

## Evals designed before implementation

### Positive evals

- [ ] Module/state-machine tests:
- [ ] Common tests: `./gradlew :composeApp:allTests`
- [ ] Desktop compile: `./gradlew :composeApp:compileKotlinDesktop`
- [ ] Web production distribution: `./gradlew :composeApp:wasmJsBrowserDistribution`
- [ ] Android debug build: `./gradlew :composeApp:assembleDebug`
- [ ] iOS simulator framework on macOS: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`
- [ ] Safety postcondition diff:
- [ ] Disclosure/private-egress scan:

### Negative / mutation controls

- Mutation or hostile input:
- Expected fail-closed result:
- [ ] The control can distinguish a real guard from hollow success.

## Evidence lanes

| Lane | Exact subject / result |
|---|---|
| Source/static review | |
| Safety snapshot before/after | |
| Local checkout/worktree/dirty state | `PASS/FAIL/ABSENT/NOT_IMPLEMENTED/NOT_EXERCISED` |
| Local Git Town sync | `PASS/FAIL/ABSENT/NOT_IMPLEMENTED/NOT_EXERCISED/SKIPPED_BY_POLICY` |
| Exact-head local verification | |
| Publication decision | |
| Remote publication | |
| Remote ancestry | |
| GitHub trusted check | |
| Runtime/device evidence | |
| Store/release evidence | |
| Merge/promotion authority | `EXTERNAL_AUTHORITY_REQUIRED` unless repository policy already preauthorizes it |

A successful sync/build/simulator/forge commit is not automatically local-worktree, release, store, merge, legal, security, or production evidence.

## Stack graph

```text
<parent>
└── <this branch>
    └── <declared child, if any>
```

- [ ] PR base equals the declared parent branch.
- [ ] `docs/git/STACKED_PRS.md` remains accurate, or issue #14 owns the pending convergence update.
- [ ] Leaf PR does not edit shared READMEs/AGENTS/traceability unless explicitly leased.

## Publication and merge

- Publication intent: `initial-pr | ready-for-review | batched-repair | existing-review-surface-maintenance`
- Disclosure scan result:
- Post-push remote head/base/ancestry result:
- Trusted CI exact head:
- Repository-owned merge preauthorization:
- Merge result: `NOT_EXERCISED | EXTERNAL_AUTHORITY_REQUIRED | AUTO_MERGE_ELIGIBLE | MERGED`

No `git town ship`, raw force push, ruleset weakening, approval bypass, or implicit merge authority.

## Cleanup and remaining work

- Cleanup / residue state:
- Agent-created resources accounted for:
- Remaining `ABSENT`:
- Remaining `NOT_IMPLEMENTED`:
- Remaining `NOT_EXERCISED`:
- Remaining `EXTERNAL_AUTHORITY_REQUIRED`:
- Immutable rollback subject:
