# ADR-020: Provider-neutral workspace federation contracts

Status: Proposed for W0 implementation (#120)

## Context

`kotlin-auto-webview` is the experience/routing surface for a federated system whose canonical truths live in source platforms, GitHub, and specialized domain repositories. URL strings, filenames, UI-local state, Google projections, model output, and route acknowledgements must not become competing authorities.

W0 therefore needs a small portable contract layer before local persistence, GitHub adapters, Google projection, Bettor routing, or workspace UI can be implemented.

## Decision

Introduce serializable contracts under `workspace/contract`:

- `SubjectRef` / `SubjectKey` for stable logical identity and canonical authority;
- `ExternalRef` for provider locator + revision/freshness;
- `TypedEdge` for explicit graph relationships;
- `ProjectionRef` for projection identity and read-back state;
- `RouteRequest` / `RouteDecision` for proposals that never grant execution authority;
- `EvidenceReceiptRef` for receipt identity without embedding receipt payloads;
- `ChangeProposal` for manual/external edits that require canonical review;
- `SyncReceipt` for outbox/read-back/retry/conflict state.

The JSON schema under `schemas/workspace/` is an interchange aid. Kotlin constructors remain the primary fail-closed runtime admission surface for W0.

## Hard invariants

```text
URL != IDENTITY
PROJECTION != AUTHORITY
ROUTE_REQUEST != EXECUTION_AUTHORITY
RECEIPT_REF != RECEIPT_CONTENT
PRIVATE SUBJECT != PUBLIC SERIALIZATION
ACK != READ_BACK_VERIFIED
```

`PublicSubjectProjection` intentionally contains no raw authority owner id, external id, URL, version, or digest for non-public subjects. It exposes only the opaque/redacted logical id, subject classification, authority kind, and external provider kinds.

A `ProjectionRef` or `SyncReceipt` may enter a read-back-verified state only when written and read-back digests match. A conflict requires both digests and requires them to differ.

## Non-goals

W0 does not implement:

- SQLDelight persistence or durable outbox;
- GitHub/Google/Bettor network adapters;
- private repository federation;
- content-rights admission;
- domain claim or Skill qualification;
- user/payment outcomes;
- execution authority.

Those remain owned by W1+ and specialized repositories.

## Consequences

Later atoms can consume one exact contract rather than inventing provider-specific payloads. The cost is intentionally strict input validation and the need for explicit promotion when new providers, subject kinds, relations, or evidence ceilings are added.

## Verification

W0 tests require:

- serialization round-trip;
- unknown provider rejection;
- private-to-public redaction;
- verified projection/read-back digest equality;
- route exact-subject requirement and no execution-authority promotion;
- sync conflict/read-back invariants;
- canonical SHA-256 representation.

PASS proves only portable contract behavior. It is not live connector, synchronization, runtime, legal, or product evidence.
