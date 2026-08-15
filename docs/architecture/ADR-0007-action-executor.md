# ADR-0007 — Freshness-bound browser action executor

- Status: Accepted for portable execution gate
- Issue: #11
- Branch: `feat/accessibility-action-executor`
- Parent: `feat/native-capability-contracts`
- Evidence level: deterministic common-code coordinator and fake-platform tests

## Context

The existing Observer provides page URL, capture time, accessible role/name, input type, and stable element fingerprints. `CapabilityRegistry` decides whether an `AgentAction` is allowed or requires confirmation, and `LocalDispatcher` gives direct user input temporal priority.

None of those layers authorizes a stale selector, coordinate-only click, raw model string, or action against a page that has changed after confirmation. A bounded executor is required between dispatcher admission and any future Android/iOS/Desktop renderer adapter.

## Decision

Introduce a portable executor contract with these boundaries:

1. `BrowserActionProposal` is typed and contains no CSS/XPath selector or screen coordinate.
2. The exact dispatcher state must be `EXECUTING`, and its pending `AgentAction.id` must match the proposal.
3. The current `PageContext` URL and capture subject must match the proposed page and remain inside a bounded freshness window.
4. A `BrowserActionConfirmationReceipt` must match the exact proposal/action/page/target identities and remain fresh.
5. `BrowserActionPlatform.resolve` resolves a stable fingerprint into zero, one, or multiple current targets.
6. Common code revalidates page identity, fingerprint, role, accessible name, visibility, enablement, editability, and sensitivity.
7. `BrowserActionPlatform.perform` receives only a typed command and an active cancellation signal tied to current user interaction.
8. Timeouts and platform failures preserve whether a side effect is known to be absent, applied, or unknown.

The platform adapter is responsible for executing against its renderer and honoring the cancellation signal before the side effect. This Stack slice provides no platform implementation.

## Execution state machine

```text
VALIDATING_AUTHORITY
├── user interacting            -> REJECTED_USER_PREEMPTED
├── dispatcher not EXECUTING    -> REJECTED_DISPATCHER
└── action identity mismatch    -> REJECTED_ACTION_ID

VALIDATING_PAGE
├── URL mismatch                -> REJECTED_PAGE_URL
├── capture subject mismatch    -> REJECTED_PAGE_SNAPSHOT
└── stale/future snapshot       -> REJECTED_PAGE_STALE

VALIDATING_CONFIRMATION
├── absent                      -> REJECTED_CONFIRMATION_REQUIRED
├── subject mismatch            -> REJECTED_CONFIRMATION_MISMATCH
└── stale                       -> REJECTED_CONFIRMATION_STALE

RESOLVING_TARGET
├── zero matches                -> REJECTED_NOT_FOUND
├── multiple matches            -> REJECTED_AMBIGUOUS
└── user interaction begins     -> CANCELLED

REVALIDATING_TARGET
├── page/fingerprint/semantics changed -> REJECTED
├── hidden/disabled/non-editable       -> REJECTED
├── password/payment/secret/cross-origin -> REJECTED
└── user interaction begins            -> CANCELLED

EXECUTING
├── platform completed          -> SUCCEEDED
├── cancelled before side effect-> CANCELLED
├── platform rejected           -> REJECTED
├── platform failed             -> FAILED(side-effect state)
└── timeout                     -> TIMED_OUT(side-effect state)
```

## Identity and freshness

The proposal is bound to:

```text
proposal ID
AgentAction ID
page URL
PageContext.capturedAtEpochMs
target fingerprint
expected role/accessibility name
confirmation receipt
```

A matching URL alone is insufficient. The capture subject must also match, because a single-page application can replace interactive content without changing the URL.

## Sensitive-target law

Execution is denied when the resolved target is classified as:

```text
PASSWORD
PAYMENT
SECRET
CROSS_ORIGIN
```

The executor also rejects known sensitive input types such as password, card-security, payment, token, and secret fields. Platform adapters may classify more targets as sensitive; they may not weaken this baseline.

## User-preemption law

Direct user interaction is checked:

- before any platform resolution;
- after resolution;
- before command dispatch;
- through the cancellation signal while the platform prepares the side effect.

A platform adapter must return `CancelledBeforeSideEffect` only when it can prove no effect happened. Timeout after execution begins is reported with `UNKNOWN` side-effect state rather than fake rollback success.

## Invariants

- `INV-EXEC-001`: no raw model text becomes a selector, coordinate, script, or platform call.
- `INV-EXEC-002`: dispatcher execution state and exact action identity precede target resolution.
- `INV-EXEC-003`: page URL, capture subject, and age are revalidated.
- `INV-EXEC-004`: confirmation is exact-subject and freshness-bound.
- `INV-EXEC-005`: one stable fingerprint must resolve to exactly one current target.
- `INV-EXEC-006`: role, accessible name, visibility, enablement, and editability cannot silently drift.
- `INV-EXEC-007`: sensitive and cross-origin targets fail closed.
- `INV-EXEC-008`: user input preempts before the side effect.
- `INV-EXEC-009`: timeout/failure never implies rollback when side-effect state is unknown.
- `INV-EXEC-010`: platform failure messages and fill payloads do not enter portable result messages.

## Negative controls

The common test suite must fail when a mutation:

- executes while dispatcher is not `EXECUTING`;
- accepts another action or proposal receipt;
- treats a matching URL as sufficient after snapshot change;
- accepts stale confirmation;
- selects the first of multiple fingerprint matches;
- ignores role/name/visibility/editability changes;
- fills password/payment/secret/cross-origin targets;
- ignores user input before or during execution;
- converts timeout after execution begins into a clean no-effect result;
- leaks a platform error payload into an execution result;
- adds selector or coordinate authority to `BrowserActionProposal`.

## Consequences

Future platform adapters can implement renderer-specific fingerprint resolution and typed command execution without owning policy, HITL, or page-freshness decisions. Android/iOS differences remain explicit.

The design is intentionally stricter than generic browser automation. It prioritizes bounded user authority over best-effort completion.

## Non-goals and evidence boundary

This ADR does not implement or prove:

- Android WebView, WKWebView, or KCEF action adapters;
- arbitrary-site automation permission or store-policy acceptance;
- background/headless execution;
- cross-origin iframe access;
- physical-device timing, cancellation, accessibility, or renderer behavior;
- transaction compensation after a partially applied action;
- release, merge, security approval, or production readiness.

A green common test suite proves only the portable gate and fake-platform behavior at its exact subject.
