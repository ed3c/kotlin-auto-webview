# Live Wave-1 implementation surface lock

This directory is the code-level handoff between the completed live-evidence
preflight (`#172` / Draft PR `#175`) and the first parallel implementation
wave (`#165`–`#168`).

It solves one narrow problem:

> A new Worker must consume exact repository bytes and known missing surfaces,
> not a conversation summary, mutable branch name, or guessed interface.

## Stage verdict

```text
LIVE_EVIDENCE_PREIMPLEMENTATION_READY   PASS
LIVE_WAVE1_IMPLEMENTATION_SURFACES_LOCKED  PENDING_EXACT_HEAD_CI

L2 live GitHub evidence   NOT_EXERCISED
L3 live Google evidence   NOT_EXERCISED
L4 live Bettor evidence   NOT_EXERCISED
L5 live domain evidence   NOT_EXERCISED
```

`contract-lock.json` is the machine authority for this directory. Markdown
explains it but cannot widen it.

## Shadow findings

### L2 — GitHub

W2 already contains a Ktor-based, read-only `api.github.com` adapter with an
optional externally supplied token, bounded pagination, response identity
checks, and explicit failure states.

Therefore #165 must **not** add a second GitHub REST client. Its first work is:

```text
existing W2 transport
→ exact public repository/Issue/PR/commit/check selection
→ live public canary
→ W2 mapper
→ W1 local read-back
→ sanitized L2 receipt
```

An authenticated/private-repository canary remains separately blocked on
credential scope and repository access.

### L3 — Google

W3 contains the provider-neutral `GoogleProjectionTransport` port and the
revision/read-back saga. A concrete Drive/Docs/Sheets transport is absent.

The first safe implementation is the concrete adapter behind the existing
port. OAuth consent, account selection, scopes, organization/DLP admission,
and the test file remain external capabilities.

### L4 — Bettor

W4 contains `RouteProposalPacket`, `RouteProposalSink`, and the
`orchestrate.work` route binding. Bettor already has Worker Gateway contracts,
but its current pinned manifest is `fixture_only` and
`live_matrix_state=NOT_EXERCISED`.

The missing load-bearing surface is a capability-workspace consumer owned by
`bettor-arena#197`; KAW must not duplicate LoopX, Worker, Gate, or ledger
authority.

### L5 — Domain authority

W4 contains the `verify.claim` route binding. Truth Verify Loop has an exact
semantic-verifier receipt schema, but neither the KAW receipt-reference adapter
nor the Truth Verify KAW canary producer exists.

KAW validates the returned repository/commit/tree/subject/receipt digest and
projects the owner verdict. It never computes, upgrades, or suppresses that
verdict.

## Start DAG

```text
#176 exact surface lock
├─ #165 L2-GH
│   ├─ code/public-canary prep may start
│   └─ private canary waits for GitHub scope
├─ #166 L3-GOOGLE
│   ├─ adapter/test prep may start
│   └─ live canary waits for account/scopes/file
├─ #167 L4-BETTOR
│   ├─ KAW adapter prep may start
│   └─ live handoff waits for bettor-arena#197 + runtime capability
└─ #168 L5-DOMAIN
    ├─ KAW adapter prep may start
    └─ live receipt waits for truth-verify-loop#47
```

## Completion DAG

```text
exact implementation head
→ deterministic tests and planted negative controls
→ external capability bound outside repository
→ one literal live operation
→ sanitized lane receipt
→ #171 convergence
```

A Worker can be code-ready without being account-ready, and account-ready
without having a live receipt.

## Data flow

```text
exact source bytes
→ typed provider/domain adapter
→ existing W2/W3/W4 boundary
→ W1 local projection or exact receipt-reference validation
→ W6 lane-specific receipt
→ public-safe denominator
```

## Non-claims

This directory does not prove:

- credentials or OAuth consent;
- private repository or Drive access;
- live Bettor deployment/Worker execution;
- semantic truth;
- physical-device behavior;
- user outcome;
- merge, release, store, legal, or production approval.
