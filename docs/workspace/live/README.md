# Federated Capability Workspace — Live Evidence Phase

This directory is the Phase-1 preimplementation router for issues #164–#172.

The deterministic public foundation is complete on exact Draft-PR subjects:

| Atom | Issue / PR | Exact head | Exact workflow |
|---|---|---|---|
| W0 federation contracts | #120 / #136 | `fba4a7f1c7cb8ca014d5a3cfe083fd9beaea4c5c` | `32248605485` |
| W1 local registry/outbox | #121 / #139 | `286f588226be7b0f9ecb63042e3eaefd5bc77dd7` | `32332217916` |
| W2 GitHub WorkGraph | #122 / #158 | `3294fb2b4d86fef91f3f2c63e28718c490147808` | `32345280914` |
| W3 Google projection saga | #123 / #160 | `95754e2a7ea6a09da030da3803313fe49641b677` | `32377060202` |
| W4 federation router | #124 / #161 | `56eb824866e7e74d63a4297748c647cff738db51` | `32379913900` |
| W5 read-only workspace | #125 / #162 | `f0e37a4f2b39dd825bfd379d42f96c29ce887f37` | `32389825474` |
| W6 evidence denominator | #126 / #163 | `c19d4e561cb09cb1c6c96c2b0f8df0c88b7d987b` | CI `32391189801`; Evidence `32391189864` |

All PRs above remain Draft/Open/Unmerged. Issue closure means the bounded atom passed its own exact-head checks, not that `main`, live providers, devices, users, merge, release, or production are complete.

## Phase-1 objective

Close applicable live evidence lanes without laundering deterministic evidence:

```text
L2 LIVE_GITHUB_CONNECTOR
L3 LIVE_GOOGLE_PROJECTION_ACCOUNT
L4 LIVE_BETTOR_HANDOFF
L5 LIVE_DOMAIN_AUTHORITY_RECEIPT
L6 PHYSICAL_DEVICE
L7 USER_OUTCOME
```

## Owners

| Atom | Issue | Git parent | Start state | Literal maximum claim |
|---|---:|---|---|---|
| L2-GH | #165 | W2 PR #158 | READY_FOR_IMPLEMENTATION_PREP | exact read-only GitHub connector |
| L3-GOOGLE | #166 | W3 PR #160 | READY_FOR_IMPLEMENTATION_PREP | exact admitted Google account projection |
| L4-BETTOR | #167 | W4 PR #161 | READY_FOR_CROSS_REPO_PREP | exact KAW→Bettor handoff |
| L5-DOMAIN | #168 | W4 PR #161 | READY_FOR_CROSS_REPO_PREP | exact domain receipt validation |
| L6-DEVICE | #169 | W5 PR #162 | BLOCKED_EXTERNAL_DEVICE | exact physical-device UI behavior |
| L7-USER | #170 | external outcome | BLOCKED_ON_LIVE_VERTICAL_SLICE | exact consented usability outcome |
| P1-EVIDENCE | #171 | W6 PR #163 | BLOCKED_ON_LIVE_RECEIPTS | denominator convergence |
| P1-PREP | #172 | W6 PR #163 | CURRENT | this preimplementation package |

Cross-repository consumers:

- `ed3c/bettor-arena#197` owns the Bettor side of L4.
- `ed3c/truth-verify-loop#47` owns the first domain receipt canary for L5.

## Edge classes

```text
Git parent
  means a branch consumes unmerged bytes from exactly one parent.

Start dependency
  means work may begin after a named interface/receipt is readable.

Completion dependency
  means the atom cannot close until an exact receipt or explicit non-PASS result exists.

Evidence dependency
  means a receipt is referenced and verified without importing its Git ancestry.

External-authority edge
  means account, credential, device, consent, legal, store, merge, or release action is not Agent-owned.
```

Do not collapse these edges.

## Start DAG

```text
W6 #126 exact denominator
├─ #165 L2 live GitHub        [Git parent: W2]
├─ #166 L3 live Google        [Git parent: W3]
├─ #167 L4 live Bettor        [Git parent: W4; consumer: bettor-arena#197]
├─ #168 L5 live domain        [Git parent: W4; authority: truth-verify-loop#47]
└─ #169 L6 physical device    [Git parent: W5; external device authority]

#170 L7 user outcome
└─ starts only after W5 + one applicable live lane + consent/measurement contract

#171 P1 evidence convergence
└─ starts after exact lane receipts or explicit non-PASS states are readable
```

## Completion DAG

```text
#165/#166/#167/#168/#169/#170 exact results
→ #171 denominator convergence
→ independent Shadow objective review
→ public-safe zero-context handoff
→ Human merge/release decision
```

A phase may close with some lanes `NOT_EXERCISED`, `BLOCKED`, `ABSENT`, or `EXTERNAL_AUTHORITY_REQUIRED`; completeness means they remain visible, not that every lane is green.

## State Machine

```text
PREP_REQUESTED
→ FOUNDATION_REBOUND
→ OWNERS_AND_LEASES_BOUND
→ LIVE_LANES_CLASSIFIED
→ EXTERNAL_AUTHORITIES_BOUND
→ SESSION_PACKETS_READY
→ LOCAL_HANDOFF_ASSERTED_OR_ABSENT
→ PREFLIGHT_VERIFIED
→ LIVE_EVIDENCE_PREIMPLEMENTATION_READY

then, per lane:
READY_FOR_PREP
→ EXACT_SUBJECT_SELECTED
→ EXTERNAL_CAPABILITY_BOUND
→ EXECUTION_ADMITTED | BLOCKED
→ RUNNING
→ RECEIPT_READ_BACK
→ PASS | FAIL | BLOCKED | NOT_EXERCISED | EXTERNAL_AUTHORITY_REQUIRED
```

## Data flow

```text
exact deterministic subject
→ lane-specific external capability
→ bounded live operation
→ provider/device/domain-owned observation
→ immutable exact receipt
→ disclosure scan
→ W6/P1 verifier
→ local/read-only Workspace projection
```

## Hard laws

```text
FIXTURE_PASS != LIVE_PROVIDER_PASS
CI_PASS != PHYSICAL_DEVICE_PASS
ACCOUNT_ACCESS != CONTENT_RIGHTS
ROUTE_ACK != WORK_EXECUTED
WORK_EXECUTED != DOMAIN_VERDICT
DOMAIN_RECEIPT_REF != RECEIPT_CONTENT
USER_CLICK != USER_OUTCOME
ISSUE_CLOSED != MERGED_OR_PRODUCTION
```

## Current verdict

```text
DETERMINISTIC_W0_W6_FOUNDATION = CLOSED
LIVE_EVIDENCE_PREIMPLEMENTATION = MATERIALIZED_BY_#172
L2_L7_EXECUTION = NOT_STARTED_OR_EXTERNAL_BLOCKED
LOCAL_HANDOFF_EXECUTION_QUEUE = ABSENT
EPIC_#164 = OPEN
EPIC_#119 = OPEN
```
