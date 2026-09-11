# ADR-0030: Deterministic postcondition verification and effect ledger

## Status

Accepted for the E1 first vertical slice. Persistence durability and platform truth remain separately evidenced.

## Decision

A platform callback is dispatch evidence only. The common verifier binds an exact proposal/capability/action/subject/target/verifier-version identity to typed precondition and postcondition digests. The only definitive postcondition outcomes are `APPLIED` and `NO_EFFECT`; stale, contradictory, missing-plan, observer-loss, and inconclusive evidence produce `UNKNOWN` with reconciliation required.

The effect ledger is an explicit state machine:

```text
PROPOSED
→ PRECONDITION_CAPTURED
→ DISPATCH_ADMITTED
→ DISPATCHING
├─ NOT_DISPATCHED / USER_ACTION_REQUIRED / proven pre-effect failure → TERMINAL_NONE
├─ DISPATCHED → VERIFYING
│  ├─ verified true → TERMINAL_APPLIED
│  ├─ verified false → TERMINAL_NONE
│  └─ inconclusive / observer loss / contradictory → TERMINAL_UNKNOWN
└─ uncertain platform failure → TERMINAL_UNKNOWN
```

Event IDs make exact replays idempotent. Illegal state regressions fail closed. `UNKNOWN` blocks automatic retry. The in-memory store is explicitly marked `NON_DURABLE_FIXTURE`; a later persistence adapter must advertise `DURABLE_ADAPTER` and prove its own receipts before durability can be claimed.

## Invariants

- No callback, intent launch, process status, MCP response, or `PendingUserAction` directly yields `APPLIED`.
- Identity mismatch never reuses evidence across proposal, target, package/window/generation, verifier, or version.
- Evidence payloads are digests plus bounded enums; raw platform exceptions and secret field values have no field in the portable record.
- Terminal `UNKNOWN` always requires reconciliation and is not retryable automatically.
- Store durability is data, not an inferred claim.

## Evidence ceiling

A green common/KMP matrix proves deterministic verifier and effect-state semantics against fixtures only. It does not prove an Android adapter emits truthful observations, real persistence is durable, a physical side effect happened, compensation is safe, merge, release, or production readiness.
