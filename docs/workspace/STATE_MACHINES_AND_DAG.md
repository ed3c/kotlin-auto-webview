# Workspace State Machines and DAG

## Realm topology

```text
Source / provider realm
→ Domain authority realm
→ GitHub work/evidence realm
→ KAW local projection/router realm
→ Google human projection realm
→ outcome foldback
```

No downstream realm can manufacture upstream rights, identity, claim truth, Skill qualification or work evidence.

## W0-W7 start-readiness DAG

```text
#119 epic
└── #120 W0 federation contracts
    ├── #121 W1 local subject graph + outbox/inbox
    ├── #122 W2 GitHub WorkGraph adapter
    ├── #123 W3 Google projection adapter
    │   └── requires #121 durable outbox semantics
    ├── #124 W4 federation router
    │   └── Bettor consumer #197 waits for immutable W0 subject
    └── #125 W5 read-only Workspace UI
        └── consumes W1/W2 and W4 ports

#126 W6 evidence convergence consumes applicable W0-W5 exact subjects
#127 W7 docs/preimplementation convergence [current]
```

## Completion-readiness DAG

```text
W0 exact contract receipt
→ W1 local graph/outbox
→ {W2 GitHub adapter, W3 Google projection, W4 router}
→ W5 read-only vertical slice
→ W6 exact cross-system evidence
→ later root/shared convergence
→ live/provider/private/device lanes as separately admitted
→ user outcome
```

A missing W3 does not prevent a GitHub-only local MVP. A missing Google projection remains explicit and cannot be substituted by manual Sheets/Docs maintenance.

## Federation lifecycle

```text
INTENT_CAPTURED
→ SUBJECTS_RESOLVED
→ AUTHORITY_RESOLVED
→ PRIVACY_RIGHTS_EVIDENCE_CLASSIFIED
→ ROUTE_SELECTED
→ DESTINATION_ADMISSION
├─ ADMITTED
├─ DEGRADED
├─ BLOCKED
└─ EXTERNAL_AUTHORITY_REQUIRED
→ EXECUTION_OR_READ
→ RECEIPT_BOUND
→ LOCAL_PROJECTION_UPDATED
→ HUMAN_PROJECTION_REQUESTED | DONE
→ READ_BACK_VERIFIED | PROJECTION_PENDING/FAILED/CONFLICT
```

## Local registry/outbox lifecycle — W1

```text
DISCOVERED
→ LOCAL_PROJECTION_STORED
→ DIRTY | CLEAN
DIRTY → OUTBOX_PENDING
→ DISPATCHING
→ ACK_RECEIVED
→ READ_BACK_VERIFIED
→ CLEAN

DISPATCHING
├─ transient failure → RETRY_SCHEDULED
├─ revision mismatch → CONFLICT
├─ policy denial → BLOCKED
└─ deletion/revocation → TOMBSTONED
```

Transport ACK without read-back cannot enter `CLEAN` for destinations that require read-back.

## GitHub WorkGraph lifecycle — W2

```text
REPOSITORY_BOUND
→ WORK_SUBJECT_RESOLVED
→ CURRENT_ISSUE_PR_COMMIT_STATE_READ
→ EXACT_CHECK_SUBJECT_READ
→ TYPED_EDGES_BUILT
→ EVIDENCE_CEILING_CLASSIFIED
→ LOCAL_PROJECTION

branch/check movement
→ STALE
→ REFETCH
```

## Google projection lifecycle — W3

```text
PROJECTION_REQUESTED
→ FILE_IDENTITY_BOUND
→ REVISION_PRECONDITION_BOUND
→ OUTBOX_EVENT
→ BATCH_WRITE
→ DRIVE/DOC/SHEET READ_BACK
→ SUBJECT_AND_DIGEST_MATCH
├─ yes → SYNCED
├─ no → CONFLICT
└─ unavailable → RETRY/BLOCKED
```

Manual edits:

```text
GOOGLE_REVISION_CHANGED
→ CHANGE_PROPOSAL_CREATED
→ CANONICAL_OWNER_REVIEW
→ ACCEPTED | REJECTED | EXTERNAL_AUTHORITY_REQUIRED
→ regenerate projection
```

## Router lifecycle — W4

```text
ROUTE_REQUESTED
→ SUBJECT/FRESHNESS_CHECK
→ CAPABILITY_RESOLUTION
├─ UNIQUE_OWNER
├─ AMBIGUOUS
└─ UNKNOWN
UNIQUE_OWNER
→ DESTINATION_DATA_CLASS_CHECK
→ REQUEST_EMITTED
→ DESTINATION_RECEIPT_BOUND | DENIED | TIMEOUT
```

`AMBIGUOUS` and `UNKNOWN` fail closed. No nearest-repository guess.

## UI lifecycle — W5

```text
WORKSPACE_OPEN
→ LOCAL_GRAPH_LOAD
→ CURRENT_EXTERNAL_STATES_REFRESH
→ READ_ONLY_VIEWS_READY
→ USER_SELECTS_SUBJECT
→ EVIDENCE/AUTHORITY/PROJECTION/WORK DETAILS
→ OPTIONAL ROUTE_PROPOSAL
```

User edits may change local notes, selections and proposals but not owner verdict fields.

## Evidence lifecycle — W6

```text
SUBJECT_PINNED
→ TEST_ENVIRONMENT_PINNED
→ POSITIVE + PLANTED_DISAGREEMENT RUN
→ RECEIPT_CAPTURED
→ IDENTITY/PRIVACY/LANE VALIDATED
→ PASS | FAIL | ABSENT | NOT_EXERCISED
```

Cross-lane promotion is forbidden.

## Directory → owner → input/output

| Planned path | Issue | Input | Output | Forbidden coupling |
|---|---:|---|---|---|
| `workspace/contract/` | #120 | provider-neutral identities | stable federation DTOs | I/O, owner verdicts |
| `workspace/registry/` | #121 | SubjectRefs/edges | local graph projection | global truth decisions |
| `workspace/sync/` | #121 | local deltas | durable outbox/inbox | destination-specific business truth |
| `workspace/github/` | #122 | GitHub metadata | WorkGraph subjects/edges | local Git/Git Town claims |
| `workspace/google/` | #123 | canonical projection event | ProjectionRef/SyncReceipt | canonical status mutation |
| `workspace/routing/` | #124 | intent + exact subjects | RouteDecision/request | execution/qualification/legal authority |
| `workspace/ui/` | #125 | local/external projections | native views/proposals | hidden authority mutation |
| `tests|receipts/workspace/` | #126 | exact test subjects | literal evidence receipts | evidence promotion |

## Cross-repository route DAG

```text
KAW #124
├─ VERIFY_CLAIM       → truth-verify-loop
├─ COMPILE_SOURCE     → ai-content-notes
├─ EVALUATE_MARKET    → ai-product-notes
├─ ROUTE_CAPABILITY   → tech-implementation-atlas
├─ RESOLVE_METHOD     → skills-shared
├─ QUALIFY_SKILL      → Skill.md-native
├─ RUN_EXPERIMENT     → blackbox-auto-research / product owner
├─ RESOLVE_RUNTIME    → runtime-env
└─ ORCHESTRATE_WORK   → bettor-arena #197
```

Every destination remains free to return `DENIED`, `NOT_IMPLEMENTED`, `NOT_EXERCISED` or `EXTERNAL_AUTHORITY_REQUIRED`.

## Shadow block states

```text
BLOCKED_UNSTABLE_IDENTITY
BLOCKED_PRIVATE_EGRESS
BLOCKED_AUTHORITY_CONFLICT
BLOCKED_PROJECTION_SPLIT_BRAIN
BLOCKED_STALE_SUBJECT
BLOCKED_ROUTE_AMBIGUOUS
BLOCKED_EVIDENCE_PROMOTION
BLOCKED_LICENSE_RIGHTS_UNKNOWN
```

Blocking one transition does not stop unrelated safe read-only work.
