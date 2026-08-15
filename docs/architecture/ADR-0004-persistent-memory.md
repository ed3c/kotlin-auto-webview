# ADR-0004: Persistent local semantic memory and audit evidence

- Status: proposed
- Issue: #8
- Parent: `build/runtime-dependency-admission`
- Scope: repository-owned SQLDelight persistence only

## Decision

Persist the existing `SemanticCache` contract behind a SQLDelight adapter and add a separate append-oriented audit evidence store. Persistence remains local. This ADR does not add OpenClaw connectivity, remote authority, encryption-at-rest claims, telemetry, background upload, or production migration evidence.

The database owns two independent tables:

```text
semantic_cache_record
  id PK
  source_url
  title
  summary
  content
  created_at_epoch_ms
  tags_json

audit_event
  sequence PK AUTOINCREMENT
  at_epoch_ms
  category
  message
  metadata_json
```

The cache table is replaceable by stable record ID. The audit table has no update operation and no delete-by-ID operation. Retention can only prune records older than an explicit epoch boundary.

## State machine

```text
CACHE
ABSENT -> UPSERTED -> QUERYABLE
   ^          |           |
   |          +-> REPLACED|
   +---- REMOVE / CLEAR <-+

AUDIT
RAW_EVENT -> SANITIZED -> APPENDED -> READ_ONLY_QUERY
                                |
                                +-> RETENTION_PRUNE_BY_TIME
```

## Invariants

1. Cache query ordering is deterministic for equivalent inputs: relevance descending, creation time descending, ID ascending.
2. A negative cache result limit yields an empty result and never expands work.
3. Audit writes pass through `AuditSanitizer` before storage.
4. Metadata keys associated with passwords, secrets, tokens, authorization, cookies, and payment data are persisted only as `[REDACTED]`.
5. Secret-like values, payment-card-like numbers, and private-key blocks are redacted from messages and non-sensitive-key values.
6. Audit evidence cannot be updated in place.
7. Retention is explicit time-bound pruning, not arbitrary evidence mutation.
8. Database success is not evidence of OpenClaw L2 connectivity, pairing, encryption at rest, physical-device behavior, or production readiness.

## Failure behavior

Database and migration failures propagate to the owning caller. They are not converted into fake cache hits or fake audit success. Corrupt serialized `tags_json` or `metadata_json` fails soft at the decoding boundary by returning an empty collection while preserving the surrounding record identity and timestamps.

## Migration policy

SQLDelight `verifyMigrations` remains enabled by the parent build-foundation slice. Any destructive schema change must ship with an explicit migration and must preserve or intentionally transform existing records. This branch introduces the initial schema only; production upgrade receipts remain `NOT_EXERCISED`.

## Evidence boundary

A green exact-head CI matrix proves that the schema generates, the adapters compile across the KMP target matrix, and pure common tests cover deterministic ranking and redaction rules. It does not prove restart persistence on every physical target, filesystem durability, encryption at rest, corruption recovery on a real database file, backup/restore, OpenClaw synchronization, store readiness, merge, or production deployment.

## Negative controls

- No endpoint, credential, peer identity, WebSocket, SSE session, or network request may be added in this slice.
- No password, payment, token, API key, authorization header, cookie, or private key fixture may survive audit sanitization.
- No cache or audit success may be relabeled as remote connectivity or authorization.
- No build dependency or project license is changed in this child branch.
