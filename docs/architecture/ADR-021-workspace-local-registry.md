# ADR-021: Local workspace registry, durable outbox, and inbox

Status: Accepted for W1 implementation (#121, Draft PR #139)

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

Direct `PENDING → READ_BACK_VERIFIED`, terminal reopening, changing sync identity/target, and changing attempt counts outside `markWriteSent` are rejected. The issue-level `BLOCKED` outcome is represented by terminal `FAILED` plus an explicit error code in the W0 contract; it does not grant or imply execution authority.

## Authority boundary

```text
LOCAL ROW != CANONICAL TRUTH
OUTBOX PENDING != REMOTE MUTATION
WRITE_ACKNOWLEDGED != READ_BACK_VERIFIED
INBOX CHANGE != CANONICAL CHANGE
CACHE CORRUPTION != PERMISSION TO INVENT STATE
```

Only W0 metadata contracts are stored by this plane. It does not store arbitrary source bytes and exposes no network capability. Private subjects may exist in the local database, but public serialization remains the W0 redacted projection path; W1 adds no public export shortcut.

## Durability, freshness, and idempotency

Subject and edge writes use monotonic local observation timestamps. An older write cannot replace a newer projection, a stale tombstone cannot hide a newer subject, and a tombstoned subject can be rebuilt only by a newer observation. An existing edge ID cannot be rebound to different endpoints or a different relation.

Outbox events use an explicit unique `dedupe_key`. Duplicate enqueue attempts do not create a second event. File-backed desktop integration tests close and reopen the SQLite database before dispatch/decision processing.

Inbox proposals use proposal ID as an idempotency key. A proposal enters only as `PROPOSED`; acceptance/rejection requires a reviewer, cannot alter the requested change, and is terminal in the local inbox.

## Row/payload integrity

Every decoded row is checked against its indexed columns:

- subject key and tombstone marker;
- edge ID, endpoints, and relation;
- sync event ID, canonical subject, state, and attempt count;
- proposal ID, canonical subject, source projection, and state.

Malformed JSON, unknown enum values, or column/payload disagreement fail closed. Raw row counts remain observable so repair tooling can distinguish storage presence from admitted decoded state.

## Schema migration

W1 introduces AppDatabase schema v3. Migration `2.sqm` starts from the pinned version-2 snapshot. Because some remote mutation carriers cannot safely transport expanded SQLite bytes, the repository stores a digest-pinned `2.db.gz`; the Gradle graph deterministically materializes `2.db`, verifies compressed and expanded SHA-256 values, and then runs the existing readable-snapshot and SQLDelight migration gates.

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

PASS proves local SQLDelight schema/migration, projection persistence, stale-write refusal, tombstone/rebuild, stable edge identity, file-backed reopen, idempotent enqueue, retry/conflict persistence, state-machine rejection, row/payload fail-closed behavior, and inbox proposal semantics only. It is not live synchronization evidence.
