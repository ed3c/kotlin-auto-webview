# ADR-024: Provider-neutral federation router

Status: proposed implementation slice for W4 / issue #124.

## Context

W0 defines `RouteRequest` and `RouteDecision`, but those contracts intentionally do not decide which authority owns a capability. Kotlin Auto WebView needs a deterministic way to validate a requested destination and emit a typed proposal without embedding execution authority or domain verdict logic.

The router must also preserve exact-subject identity. A subject key alone is insufficient when the caller observed a specific version or digest. Reusing a correlation/request ID for a different intent must not be treated as an idempotent replay.

## Decision

W4 introduces a provider-neutral `FederationRouter` with four bounded inputs:

1. a `FederationRouteCatalog` that binds one exact capability ID to one route class and one owner;
2. a `FederationRouteSubjectSource` that resolves current `SubjectRef` values;
3. a `RouteRequestLedger` that distinguishes new, idempotent-replay, and semantic-conflict request IDs;
4. a `RouteProposalSink` that may acknowledge, deny, or time out a proposal, but cannot return execution success.

Routing flow:

```text
RouteRequest + exact version/digest expectations
→ resolve one capability binding
→ require requested owner == bound owner
→ cap requested evidence ceiling
→ read back every exact subject
→ reject missing/stale identity, version, or digest
→ enforce destination data-class ceiling
→ claim request ID against semantic fingerprint
→ emit typed proposal
→ validate destination acknowledgement
→ ADMITTED route decision | DEFERRED | REJECTED
```

A successful route decision always has `executionAuthorityGranted=false`.

## Route classes

The portable route classes are:

```text
VERIFY_CLAIM
COMPILE_CONTENT
EVALUATE_MARKET
RESOLVE_METHOD
QUALIFY_SKILL
RUN_EXPERIMENT
RESOLVE_RUNTIME
ORCHESTRATE_WORK
OPEN_WORK_ITEM
PROJECT_HUMAN_VIEW
```

Broad route classes do not select a destination by themselves. Capability IDs remain specific enough to avoid ambiguity, for example:

```text
compile.content.cards
compile.requirements.capabilities
```

An ambiguous capability table is rejected at construction time.

## Data-class admission

The standard public catalog is deliberately `PUBLIC`-only. A host that has separately admitted a confidential destination may inject a different binding with a higher `maximumDataClass`.

Therefore:

```text
KNOWN_DESTINATION_NAME != CONFIDENTIAL_DATA_AUTHORIZATION
PRIVATE_SUBJECT != AUTOMATIC_PRIVATE_ROUTE
```

## Exact handoff

`RouteProposalPacket` carries:

- caller authority;
- intent;
- route class;
- destination owner;
- requested evidence ceiling;
- every exact subject expectation, including expected version and/or digest.

A destination acknowledgement must echo the request ID, route class, owner, and evidence ceiling. Any mismatch is rejected.

## Request idempotency

The semantic fingerprint contains caller, intent, capability, route class, owner, evidence ceiling, and sorted exact subject version/digest expectations.

```text
same request ID + same semantics
→ IDEMPOTENT_REPLAY

same request ID + different semantics
→ REJECTED
```

The included `InMemoryRouteRequestLedger` is a deterministic implementation useful for tests and bounded process lifetimes. It is not claimed to be durable across restarts. A durable host may supply a different ledger through the same interface.

## Failure semantics

Unknown capability, wrong owner, stale subject version/digest, insufficient destination data class, excessive evidence ceiling, request-ID semantic conflict, and mismatched acknowledgement fail closed.

Destination denial and timeout are `DEFERRED`, not execution failures or evidence receipts. Destination reason strings are restricted to bounded uppercase machine codes before being copied into route decisions.

## Non-authority laws

```text
ROUTE_REQUEST != EXECUTION_AUTHORITY
ROUTE_DECISION_ADMITTED != TASK_EXECUTED
DESTINATION_ACK != DOMAIN_VERDICT
REQUEST_ID != SEMANTIC_IDENTITY
KNOWN_OWNER != DATA_EGRESS_AUTHORIZATION
GITHUB_WORK_ITEM_ROUTE != ISSUE_CREATED
GOOGLE_VIEW_ROUTE != DOC_OR_SHEET_SYNCED
```

## Evidence boundary

W4 tests can prove deterministic route selection, stale-subject rejection, data/evidence ceiling enforcement, idempotent replay, destination denial/timeout handling, and acknowledgement validation.

They do not prove Bettor execution, repository mutation, Google synchronization, provider authentication, legal/rights approval, user outcome, paid outcome, merge, or release.
