# ADR-0004 — Persistent local semantic memory and audit evidence

- Status: Accepted for portable persistence implementation
- Issue: #8
- Branch: `feat/persistent-memory`
- Parent: `build/runtime-dependency-admission`
- Evidence level: generated SQLDelight schema, common policy tests, and Desktop SQLite integration tests

## Context

The runtime currently owns an in-memory `SemanticCache` and a bounded in-memory audit flow. The source architecture assigns KMP the immediate L1 sensory-memory role while a future OpenClaw node provides deeper L2 context. Local persistence must therefore preserve deterministic L1 behavior without silently creating remote authority or allowing sensitive WebView material to survive on disk.

SQLDelight 2.3.2 is admitted by the parent Stack slice. Because the repository includes WasmJS and SQLDelight's Web Worker driver is asynchronous, this database uses generated asynchronous APIs on every target. Platform driver construction remains outside this ADR.

## Decision

Persist the existing `SemanticCache` contract behind `SqlDelightSemanticCache` and add a separate `SqlDelightAuditEvidenceStore`.

The database owns two independent tables:

```text
semantic_cache_record
  id PK
  source_url
  title
  summary
  content
  created_at_epoch_ms
  last_accessed_at_epoch_ms
  tags_json

audit_event
  sequence PK AUTOINCREMENT
  at_epoch_ms
  category
  message
  metadata_json
```

Cache rows are replaceable only by stable record ID. Audit rows have no update operation and no delete-by-ID operation. Retention uses deterministic bounded pruning; audit evidence can additionally be pruned only before an explicit epoch boundary.

## Database lifecycle

Platform code must apply this lifecycle before constructing the adapters:

```text
DRIVER_CREATED
├── user_version = 0 -> Schema.awaitCreate -> SET_VERSION -> READY
├── 0 < user_version < current -> Schema.awaitMigrate -> READY
├── user_version = current -> READY
└── create/migrate failure -> FAILED (propagate; no fake cache/audit success)
```

The first checked migration is `1.sqm`:

```text
version 1
  semantic_cache_record without access time

version 2
  add last_accessed_at_epoch_ms
  backfill from created_at_epoch_ms
  rebuild deterministic access index
  add audit_event and audit index
```

A destructive schema edit without a migration is rejected by SQLDelight migration verification and repository review.

## Runtime state machines

### Cache

```text
RAW_RECORD
→ SANITIZED
→ UPSERTED
→ RETENTION_PRUNED
→ QUERYABLE
→ MATCHES_TOUCH_ACCESS_TIME

QUERY / PUT / REMOVE / CLEAR failure
→ FAILED (propagated to caller)
```

### Audit

```text
RAW_EVENT
→ SANITIZED
→ APPENDED
→ RETENTION_PRUNED
→ READ_ONLY_QUERY

optional explicit policy action
→ PRUNE_BEFORE_EPOCH
```

## Privacy boundary

`PersistenceSanitizer` runs before every cache write:

- URL query strings and fragments are removed;
- secret-, token-, authorization-, password-, payment-, and private-key-like values are redacted from title, summary, content, and tags;
- tag lengths are bounded.

`AuditSanitizer` runs before every audit write:

- sensitive metadata keys persist only as `[REDACTED]`;
- secret-like values, bearer values, card-like numbers, and private-key blocks are removed from messages and non-sensitive metadata values;
- the category length is bounded.

Malformed serialized `tags_json` or `metadata_json` degrades to an empty collection while preserving non-secret record identity and timestamps. Corrupt SQL/schema/driver state is not swallowed.

## Determinism and retention

`PersistentMemoryPolicy` defines:

```text
maximumCacheRecords
maximumAuditEvents
maximumQueryCandidates
```

Cache retention keeps the most recently accessed rows, then newest creation time, then stable ID. Query ranking remains:

```text
relevance descending
created_at_epoch_ms descending
record ID ascending
```

Audit retention keeps the newest append sequences. The externally visible audit entry includes its immutable sequence so two equal-timestamp events remain distinguishable.

## Invariants

- `INV-MEM-001`: every persisted cache record passes through `PersistenceSanitizer`.
- `INV-MEM-002`: every persisted audit event passes through `AuditSanitizer`.
- `INV-MEM-003`: cache ranking is deterministic for equivalent inputs.
- `INV-MEM-004`: cache, audit, and query candidate growth are explicitly bounded.
- `INV-MEM-005`: audit rows cannot be updated in place or deleted by ID.
- `INV-MEM-006`: migration preserves version-1 cache records and creates the audit lane.
- `INV-MEM-007`: malformed JSON fails soft only at the serialized-field boundary; database failures propagate.
- `INV-MEM-008`: persistence grants no OpenClaw, network, capability, dispatcher, or execution authority.
- `INV-MEM-009`: asynchronous SQLDelight APIs are used consistently across JVM, Android, Native, and WasmJS.
- `INV-MEM-010`: a green database test is not encryption-at-rest, physical-device, backup, release, or production evidence.

## Verification architecture

Common tests prove:

- deterministic ranking independent of input order;
- non-positive query limits do not expand work;
- retention budgets reject invalid values before mutation;
- cache and audit sanitizers remove secret/payment/private-key fixtures.

Desktop SQLite integration tests prove on one JVM substrate:

- a file-backed cache and audit record survive driver close/reopen;
- cache/audit retention and explicit deletion behave deterministically;
- migration `1 -> 2` preserves a legacy cache record and enables audit writes;
- malformed serialized fields degrade without leaking raw corrupt payloads;
- secrets are redacted before bytes are written to SQLite.

## Negative controls

The suite or review must turn red when a mutation:

- persists a URL query/fragment or secret fixture;
- bypasses sanitizer calls;
- uses unbounded select/retention behavior;
- breaks deterministic tie ordering;
- updates or deletes an audit row by ID;
- removes the `1.sqm` migration while changing the schema;
- converts create/migrate/query failures into fake success;
- introduces an endpoint, peer, credential, telemetry, or remote action;
- relabels Desktop SQLite evidence as Android/iOS/Wasm physical durability.

## Consequences

The existing runtime can receive these adapters through dependency injection without changing its policy or projection contracts. Platform-specific driver factories and database lifecycle wiring remain separate tasks because Android, Native, Desktop, and Web Worker drivers have different storage and initialization semantics.

## Non-goals and evidence boundary

This ADR does not implement or prove:

- production platform driver factories or automatic app wiring;
- Android/iOS/Wasm physical restart durability;
- encryption at rest, secure deletion, backup/restore, or key custody;
- multi-process or cross-device concurrency;
- OpenClaw pairing/streaming or remote synchronization;
- production migration receipts, signed stores, security/legal acceptance, merge, or deployment.

A green exact-head PR matrix proves generated-schema compatibility, common behavior, and Desktop integration only.
