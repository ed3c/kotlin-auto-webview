# ADR-0008 — Semantic-router contract and deterministic lexical baseline

- Status: Accepted for baseline implementation
- Issue: #12
- Branch: `feat/local-semantic-router-contract`
- Parent: `docs/agent-integration-stack-index`
- Evidence level: deterministic common-code contract and fixed synthetic corpus

## Context

The current in-memory cache performs lexical cosine ranking inside `cache/SemanticCache.kt`. The source architecture proposes a richer local semantic router that compares current WebView context with local and streamed cache records, prunes stale data, and later allows an on-device embedding or SLM adapter.

Selecting a model before defining the contract would let a vendor artifact silently determine identity, freshness, fallback, resource, and evidence semantics. It would also couple cache storage and projection rendering to one engine.

## Decision

Introduce a separate `semantics/` contract without modifying the current cache or projection implementation in this Stack slice.

The contract contains:

- `SemanticRoutingRequest` — query, current context fingerprint, current time, threshold, and result limit;
- `SemanticRouteCandidate` — stable identity, bounded searchable content, optional context fingerprint, and optional expiry;
- `SemanticRoutingBudget` — maximum candidates, characters per candidate, total characters, and results;
- `SemanticRoute` — candidate identity, normalized score, matched terms, and source;
- `SemanticRoutingMetrics` — bounded non-sensitive counters and fallback identity;
- `SemanticRouter` — replaceable suspend adapter;
- `LexicalSemanticRouter` — deterministic Unicode-token cosine baseline;
- `ResilientSemanticRouter` — primary/fallback composition that preserves cancellation and never records exception messages.

No model or vendor is selected by this ADR.

## Routing state machine

```text
RECEIVED
→ VALIDATE_REQUEST
→ SORT_AND_DEDUPLICATE_IDENTITIES
→ APPLY_CANDIDATE_COUNT_BUDGET
→ REJECT_EXPIRED_OR_CONTEXT_MISMATCHED
→ APPLY_CHARACTER_BUDGETS
→ SCORE
→ APPLY_THRESHOLD
→ STABLE_SORT(score desc, candidate id asc)
→ APPLY_RESULT_BUDGET
→ ROUTED
```

Primary adapter failure follows:

```text
PRIMARY_RUNNING
├── success      -> ROUTED_PRIMARY
├── cancellation -> CANCELLED
└── failure      -> FALLBACK_RUNNING
                    ├── success -> ROUTED_FALLBACK
                    └── failure -> FAILED
```

A fallback result records the primary failure class only. Exception messages, endpoints, payloads, and stack traces do not enter portable metrics.

## Freshness rule

A candidate is stale when:

- its expiry is at or before `nowEpochMs`; or
- it declares a `contextFingerprint` and the active fingerprint is absent or different.

Freshness is evaluated before ranking. Similarity cannot override an identity or lifecycle mismatch.

Candidates without a context fingerprint are treated as global semantic records. A future OpenClaw adapter may add stricter origin, sequence, and stream-epoch requirements.

## Determinism rule

- Candidates are sorted by stable ID before budgeting.
- Duplicate IDs are counted and collapsed.
- Lexical scores are normalized to nine decimal places.
- Equal scores are ordered by candidate ID.
- Tags are sorted before entering searchable text.
- Operation budgets are explicit inputs, not implicit platform limits.

## Resource envelope

The baseline defaults are:

```text
maximum candidates:             128
maximum characters/candidate:  4096
maximum total characters:     65536
maximum results:                  8
```

These are deterministic operation bounds. They are not physical-device memory, latency, thermal, or battery measurements.

## Invariants

- `INV-SEM-001`: routing is separate from cache persistence and projection rendering.
- `INV-SEM-002`: stable identity and freshness are checked before similarity.
- `INV-SEM-003`: the result contains no execution or authorization authority.
- `INV-SEM-004`: equal inputs and budgets produce stable ordering across supported targets.
- `INV-SEM-005`: every unbounded input dimension has an explicit operation cap.
- `INV-SEM-006`: primary adapter failure falls back without exposing sensitive failure text.
- `INV-SEM-007`: cancellation is never relabeled as successful fallback.
- `INV-SEM-008`: no model/vendor is admitted from source prose or marketing claims.

## Fixed evaluation corpus

`docs/evals/semantic-routing/corpus.json` is synthetic and non-sensitive. It defines three top-1 queries over four candidates. The common test mirrors the corpus and requires top-1 precision and recall of `1.0` for this narrow fixture.

This is a regression contract, not evidence of product-level retrieval quality.

## Negative controls

The test suite must turn red when a mutation:

- uses input ordering to break score ties;
- scores stale or context-mismatched evidence;
- exceeds candidate/character/result budgets;
- returns duplicate candidate identities;
- leaks a primary exception message;
- swallows cancellation;
- accepts invalid thresholds or negative limits;
- adds policy, dispatcher, or action-execution authority to a route.

## Consequences

Issue #13 can compare candidate on-device engines behind the same `SemanticRouter` contract and fixed corpus. Any candidate must separately prove artifact variants, dependency identity, license/notices, physical-device latency, memory, battery, and package-size budgets.

The existing cache ranking remains unchanged until a later integration Stack slice explicitly owns the cache/semantic boundary. This avoids hidden behavior changes in a contract-only PR.

## Non-goals

This ADR does not implement:

- embeddings, vector indexes, SLMs, or remote model calls;
- SQLDelight persistence;
- OpenClaw streaming, replay protection, or backpressure;
- projection rendering or DOM anchor execution;
- capability authorization, dispatcher state changes, or HITL;
- physical-device benchmarks or store readiness.
