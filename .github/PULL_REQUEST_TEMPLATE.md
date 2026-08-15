## Stack contract

- Issue / parent issue:
- Goal:
- Non-goals:
- Base branch:
- Parent branch:
- Head branch:
- Stack class: `foundation | child | sibling | convergence | release | hotfix`
- Dependencies:
- Parallel-safe siblings:
- Rollback subject (immutable SHA/tag):

## Path lease

- Allowed paths:
- Named exclusions:
- Shared-index owner:
- [ ] No active sibling Worker owns an overlapping path.

## State and data ownership

- Runtime state machine or pipeline changed:
- Input contract:
- Output contract:
- Forbidden coupling preserved:
- Platforms affected:

## Security / privacy

- Capability/policy impact:
- Sensitive data impact:
- HITL / user-preemption impact:
- Network/identity/origin impact:
- [ ] Raw model output is not executed.
- [ ] Password/payment/secret values cannot enter context, cache, MCP, logs, or receipts.

## Evals designed before implementation

### Positive evals

- [ ] Module/state-machine tests:
- [ ] Common tests: `./gradlew :composeApp:allTests`
- [ ] Desktop compile: `./gradlew :composeApp:compileKotlinDesktop`
- [ ] Web production distribution: `./gradlew :composeApp:wasmJsBrowserDistribution`
- [ ] Android debug build: `./gradlew :composeApp:assembleDebug`
- [ ] iOS simulator framework on macOS: `./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64`

### Negative / mutation controls

- Mutation or hostile input:
- Expected fail-closed result:

## Evidence lanes

| Lane | Subject / result |
|---|---|
| Local Git Town sync | `PASS/FAIL/ABSENT/NOT_IMPLEMENTED/NOT_EXERCISED/SKIPPED_BY_POLICY` |
| Exact-head local verification | |
| Publication decision | |
| Remote publication | |
| Remote ancestry | |
| GitHub trusted check | |
| Runtime/device evidence | |
| Store/release evidence | |
| Human Admit | required |

A successful sync/build/simulator job is not automatically release or store evidence.

## Stack graph

```text
<parent>
└── <this branch>
    └── <declared child, if any>
```

- [ ] PR base equals the declared parent branch.
- [ ] `docs/git/STACKED_PRS.md` remains accurate, or issue #14 owns the pending convergence update.
- [ ] Leaf PR does not edit shared READMEs/AGENTS/traceability unless explicitly leased.

## Cleanup and remaining work

- Cleanup / residue state:
- Remaining `ABSENT`:
- Remaining `NOT_IMPLEMENTED`:
- Remaining `NOT_EXERCISED`:
- Human-owned next operation:
