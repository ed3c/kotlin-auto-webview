# ADR-021: Local workspace registry, durable outbox, and inbox

Status: Proposed for W1 implementation (#121)

## Context

W0 defines federation identities and receipts, but the KAW process still needs crash-safe local state before any GitHub, Google, Bettor, or product-specific adapter can be admitted. The local store must not become a competing global authority.

## Decision

Add SQLDelight-backed local projections for:

- `SubjectRef` metadata and tombstones;
- `TypedEdge` graph projections;
- idempotent sync outbox events with explicit state/attempt/dedupe metadata;
- external/manual `ChangeProposal` inbox records.

The outbox persists `SyncReceipt` payloads and permits only explicit state transitions:

```text
PENDING
→ WRITE_SENT
→ WRITE_ACKNOWLEDGED
→ READ_BACK_VERIFIED | CONFLICT

PENDING | WRITE_SENT | WRITE_ACKNOWLEDGED
→ RETRYABLE_FAILURE
→ WRITE_SENT | FAILED

terminal
→ CLEANED_UP
```

Direct `PENDING → READ_BACK_VERIFIED`, terminal reopening, and decreasing attempt counts are rejected.

## Authority boundary

```text
LOCAL ROW != CANONICAL TRUTH
OUTBOX PENDING != REMOTE MUTATION
WRITE_ACKNOWLEDGED != READ_BACK_VERIFIED
INBOX CHANGE != CANONICAL CHANGE
CACHE CORRUPTION != PERMISSION TO INVENT STATE
```

Only W0 metadata contracts are stored by this plane. It does not store arbitrary source bytes and exposes no network capability.

## Durability and idempotency

Outbox events use an explicit unique `dedupe_key`. Duplicate enqueue attempts do not create a second event. File-backed desktop integration tests close and reopen the SQLite database before dispatch/decision processing.

Inbox proposals use proposal ID as an idempotency key. A proposal enters only as `PROPOSED`; acceptance/rejection requires a reviewer and only changes the local proposal state.

## Schema migration

W1 introduces AppDatabase schema v3. Migration `2.sqm` starts from the committed version-2 snapshot `2.db`. The snapshot contains only the pre-W1 semantic cache and audit schema and has `PRAGMA user_version = 2`.

## Failure behavior

Malformed serialized subject/edge/outbox/inbox payloads fail closed during decode; they are not promoted into canonical state. Corrupt-row counting remains observable so later repair/tombstone logic can distinguish storage presence from decoded projection availability.

## Non-goals

W1 does not implement:

- GitHub/Google/Bettor network dispatch;
- retry timers/background schedulers;
- source-rights admission;
- private-repository discovery;
- user-facing workspace UI;
- canonical conflict resolution;
- productization semantics;
- user/payment outcomes.

## Verification ceiling

PASS proves local SQLDelight schema/migration, projection persistence, file-backed reopen, idempotent enqueue, state-machine rejection, tombstone behavior, and inbox proposal semantics only. It is not live synchronization evidence.
