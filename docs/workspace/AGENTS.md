# Workspace Agent contract

Scope: `docs/workspace/**` and future `workspace/**` implementation atoms #120–#126.

## Mandatory read order

1. repository root `AGENTS.md` and `README.md`;
2. `docs/workspace/README.md`;
3. `AUTHORITY_AND_IDENTITY.md`;
4. `STATE_MACHINES_AND_DAG.md`;
5. `TECHNOLOGY_ADMISSION.md`;
6. `CURRENT_STATE.md`;
7. exact owning issue and branch/PR/head/check state;
8. destination repository's own authority documents before emitting a route.

For Creator/media work also read `docs/security/CONTENT_PLATFORM_MEDIA_RISK_REGISTER.md` and `docs/creator/**` as required by root policy.

## Hard laws

```text
projection != authority
route request != execution authority
UI state != canonical state
URL/title != stable identity
issue close != runtime closure
PR merge != market outcome
model output != claim/Skill/legal truth
```

- Every external object is addressed by provider + stable external ID + revision/SHA/freshness where available.
- Every local `SubjectRef` names one canonical authority and evidence ceiling.
- Private repo/file/customer identities are local/private data unless an explicit sanitized envelope permits export.
- No credential, OAuth token, browser cookie/session, private key, secret value or private browsing history enters public logs, fixtures, receipts, issues or committed projections.
- Google Docs/Sheets are projections only. Manual edits become `ChangeProposal` and require canonical-owner acceptance before regeneration.
- GitHub owns issue/PR/commit/check work state, but GitHub status is not runtime, legal, store or user-outcome authority.
- KAW does not copy canonical Skill bodies from `skills-shared`, does not self-qualify Skills, and does not write Bettor's LoopX ledger/Gate verdicts.
- Destination repositories may reject, degrade or require external authority; KAW must surface that state rather than silently reroute.
- Unknown capability or ambiguous owner fails closed.
- Retries are idempotent; transport IDs never become semantic identity.

## Shadow Architect monitor

Monitor these deltas continuously:

```text
AUTHORITY_DELTA
IDENTITY_DELTA
PRIVATE_EGRESS_DELTA
PROJECTION_DELTA
ROUTING_DELTA
EVIDENCE_PROMOTION_DELTA
SYNC_CONFLICT_DELTA
LICENSE_RIGHTS_DELTA
```

L3 block if any transition:

1. makes KAW local state a global truth owner;
2. lets Google Docs/Sheets mutate canonical state directly;
3. serializes private repo/Drive/customer identity into public artifacts;
4. treats a URL/title as stable identity;
5. treats connector/API success without read-back as synchronized state;
6. lets a model/provider/Worker grant its own authority;
7. treats code license as permission for model/data/service/media content;
8. promotes issue/PR/CI state into runtime/legal/store/market closure;
9. copies a draft cross-repo contract instead of pinning an admitted immutable subject;
10. creates multiple writers for a shared canonical subject.

## Worker rule

One issue owns one path lease. Root shared files remain #98's lease until a later explicit convergence. Workspace prep #127 writes only `docs/workspace/**`.

Implementation order begins with #120. #121–#125 may start only when the exact W0 contract they consume is readable and admitted under their issue conditions. Bettor #197 remains blocked until an immutable W0 subject exists.

## Completion language

Allowed at the end of this prep branch:

```text
PREIMPLEMENTATION_CLOSED
```

Forbidden without stronger receipts:

```text
workspace implemented
Google sync working
Bettor integration working
private repo federation verified
production ready
market validated
```
