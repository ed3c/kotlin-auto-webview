# ADR-023: Google Docs/Sheets as read-back verified projections

Status: **Implemented in W3 Draft PR; live Google transport not implemented**

Tracks: #119, #123

## Context

Google Docs and Sheets are useful human-readable views of architecture, decisions, status, and traceability. They must not become a second canonical database beside GitHub/domain repositories.

W0 defines `ExternalRef`, `ChangeProposal`, `SyncReceipt`, and evidence ceilings. W1 adds the durable local outbox/inbox and fail-closed transition machine. W3 consumes those contracts without adding another raw-content database.

## Decision

W3 uses a **desired-state reconciliation saga**:

```text
canonical subject changes
→ enqueue durable SyncReceipt(subject, exact Google target)
→ begin bounded attempt
→ resolve current canonical subject + digest
→ re-check destination admission
→ pre-read exact Google file ID + revision + embedded subject/digest markers
→ conditional write against that exact revision
→ WRITE_ACKNOWLEDGED
→ read back
→ READ_BACK_VERIFIED | CONFLICT | RETRYABLE_FAILURE | FAILED
```

The outbox receipt is not an event-sourced copy of Google content. The current canonical projection payload is resolved again at dispatch. This prevents private or customer content from being duplicated into a second local persistence layer merely to drive synchronization.

## Identity

Projection identity is:

```text
canonical subject
+ projection ID
+ Google file ID
+ exact revision
```

A filename or title is display metadata only. Same-title files remain distinct when file IDs differ. Rename does not change identity.

Public receipts deliberately omit file IDs, revisions, URLs, and event IDs.

## Concurrency and manual edits

W3 pre-reads the current revision before any write and sends a conditional-write command with that revision. It never performs last-write-wins overwrite of an unexpected revision.

If a previously bound target revision changed and the target is not already equal to canonical desired state:

```text
TARGET_REVISION_CHANGED
→ no write
→ ChangeProposal when the target still declares the same canonical subject
→ FAILED/BLOCKED local receipt
→ canonical owner review required
```

A manual Google edit is therefore a proposal, not canonical truth. Accepted/rejected decisions remain owned by the canonical repository workflow.

If the revision changes between pre-read and write, the attempt becomes retryable. Retry exhaustion becomes fail-closed `FAILED`.

## Read-back law

A provider write acknowledgement is insufficient.

`READ_BACK_VERIFIED` requires all of:

- same Google file identity;
- canonical subject marker equals the expected subject;
- canonical digest marker equals the current canonical digest;
- rendered digest equals the acknowledged written digest;
- a non-blank read-back revision.

A post-write rendered digest mismatch becomes `CONFLICT`. Identity mismatch with equal bytes is blocked instead of being misrepresented as a valid projection.

## Retry semantics

W1 defines `WRITE_SENT` as the attempt boundary. Every retry must cross `WRITE_SENT`, increment attempts exactly once, and clear stale write/read-back evidence before another attempt.

W3 follows that rule even when failure happens during payload resolution or pre-read. This keeps restart/retry accounting deterministic.

## Privacy and rights

Destination admission is checked twice:

1. before enqueue;
2. again immediately before provider operations.

This lets policy/rights changes block an already queued projection.

`LOCAL_ONLY` and `EXTERNAL_AUTHORITY_REQUIRED` never write to Google. Public receipts contain no Google file IDs, revisions, URLs, event IDs, OAuth data, or private-repository locators.

## Provider boundary

This W3 slice defines `GoogleProjectionTransport` and deterministic fake-provider tests only.

It does **not** implement:

- Google OAuth or Credential Manager;
- Drive/Docs/Sheets HTTP clients;
- token persistence;
- organization authorization;
- creation of Google files;
- publication or sharing permissions;
- background scheduling;
- canonical conflict resolution;
- user/payment outcome claims.

A future live transport must consume the W3 conditional-write/read-back contract rather than bypass it.

## Evidence ceiling

Passing W3 tests proves the local saga, identity, admission, retry, conflict, proposal, and redaction semantics for deterministic fixtures. It does not prove that a real Google account, organization policy, or live provider accepted a write.
